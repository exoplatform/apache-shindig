/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.shindig.gadgets.rewrite.lexer;

import org.apache.shindig.common.uri.Uri;
import org.apache.shindig.common.ContainerConfig;
import org.apache.shindig.gadgets.Gadget;
import org.apache.shindig.gadgets.GadgetException;
import org.apache.shindig.gadgets.GadgetSpecFactory;
import org.apache.shindig.gadgets.http.HttpRequest;
import org.apache.shindig.gadgets.http.HttpResponse;
import org.apache.shindig.gadgets.rewrite.ContentRewriter;
import org.apache.shindig.gadgets.rewrite.ContentRewriterFeature;
import org.apache.shindig.gadgets.rewrite.CssRewriter;
import org.apache.shindig.gadgets.rewrite.LinkRewriter;
import org.apache.shindig.gadgets.rewrite.MutableContent;
import org.apache.shindig.gadgets.rewrite.ProxyingLinkRewriter;
import org.apache.shindig.gadgets.rewrite.RewriterResults;
import org.apache.shindig.gadgets.spec.GadgetSpec;
import org.apache.shindig.gadgets.spec.View;
import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of content rewriting.
 */
@Singleton
public class DefaultContentRewriter implements ContentRewriter {

  private final GadgetSpecFactory specFactory;

  private final ContainerConfig config;

  static final String CONTENT_REWRITE_KEY = "gadgets.content-rewrite";
  static final String INCLUDE_TAGS_KEY = "include-tags";
  static final String INCLUDE_URLS_KEY = "include-urls";
  static final String EXCLUDE_TAGS_KEY = "exclude-urls";
  static final String PROXY_URL_KEY = "proxy-url";
  static final String CONCAT_URL_KEY = "concat-url";
  static final String EXPIRES_KEY = "expires";

    @Inject
  public DefaultContentRewriter(
      GadgetSpecFactory specFactory,
      ContainerConfig config) {
    this.specFactory = specFactory;
    this.config = config;
  }

  public RewriterResults rewrite(HttpRequest request, HttpResponse original,
      MutableContent content) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(
          (content.getContent().length() * 110) / 100);
      OutputStreamWriter output = new OutputStreamWriter(baos, original.getEncoding());
      String mimeType = original.getHeader("Content-Type");
      if (request.getRewriteMimeType() != null) {
        mimeType = request.getRewriteMimeType();
      }
      GadgetSpec spec = null;
      if (request.getGadget() != null) {
        spec = specFactory.getGadgetSpec(request.getGadget().toJavaUri(), false);
      }
      if (rewrite(spec, request.getUri(),
                  content,
                  mimeType,
                  output,
                  request.getContainer())) {
        content.setContent(new String(baos.toByteArray()));
        return RewriterResults.cacheableIndefinitely();
      }
    } catch (UnsupportedEncodingException uee) {
      throw new RuntimeException(uee);
    } catch (GadgetException ge) {
      // Couldn't retrieve gadgetSpec
    }
    return null;
  }

  public RewriterResults rewrite(Gadget gadget, MutableContent content) {
    StringWriter sw = new StringWriter();
    GadgetSpec spec = gadget.getSpec();
    Uri base = spec.getUrl();
    View view = gadget.getCurrentView();
    if (view != null && view.getHref() != null) {
      base = view.getHref();
    }
    if (rewrite(spec, base, content, "text/html", sw, gadget.getContext().getContainer())) {
      content.setContent(sw.toString());
      return RewriterResults.cacheableIndefinitely();
    }
    return null;
  }

  private boolean rewrite(GadgetSpec spec, Uri source, MutableContent mc, String mimeType, Writer w, String container) {
    // Dont rewrite content if the spec is unavailable
    if (spec == null) {
      return false;
    }

    JSONObject contentRewrite = config.getJsonObject(container, CONTENT_REWRITE_KEY);

    ContentRewriterFeature rewriterFeature;
    try {
      JSONArray jsonTags = contentRewrite.getJSONArray(INCLUDE_TAGS_KEY);
      Set<String> tags = new HashSet<String>();
      for (int i = 0, j = jsonTags.length(); i < j; ++i) {
        tags.add(jsonTags.getString(i).toLowerCase());
      }

      rewriterFeature = new ContentRewriterFeature(spec,
          contentRewrite.getString(INCLUDE_URLS_KEY),
          contentRewrite.getString(EXCLUDE_TAGS_KEY),
          contentRewrite.getString(EXPIRES_KEY),
          tags);
    } catch (JSONException e) {
      return false;
    }

    if (!rewriterFeature.isRewriteEnabled()) {
      return false;
    }
    if (isHTML(mimeType)) {
      Map<String, HtmlTagTransformer> transformerMap
          = new HashMap<String, HtmlTagTransformer>();

      if (getProxyUrl(container) != null) {
        LinkRewriter linkRewriter = createLinkRewriter(spec, rewriterFeature, container);
        LinkingTagRewriter rewriter = new LinkingTagRewriter(
            linkRewriter,
            source);
        Set<String> toProcess = new HashSet<String>(rewriter.getSupportedTags());
        toProcess.retainAll(rewriterFeature.getIncludedTags());
        for (String tag : toProcess) {
          transformerMap.put(tag, rewriter);
        }
        if (rewriterFeature.getIncludedTags().contains("style")) {
          transformerMap.put("style", new StyleTagRewriter(source, linkRewriter));
        }
      }
      if (getConcatUrl(container) != null && rewriterFeature.getIncludedTags().contains("script")) {
        transformerMap
            .put("script", new JavascriptTagMerger(spec, rewriterFeature, getConcatUrl(container), source));
      }
      HtmlRewriter.rewrite(new StringReader(mc.getContent()), source, transformerMap, w);
      return true;
    } else if (isCSS(mimeType)) {
      if (getProxyUrl(container) != null) {
        CssRewriter.rewrite(new StringReader(mc.getContent()), source, createLinkRewriter(spec, rewriterFeature, container), w, false);
        return true;
      } else {
        return false;
      }
    }
    return false;
  }

  private boolean isHTML(String mime) {
    return mime != null && (mime.toLowerCase().contains("html"));
  }

  private boolean isCSS(String mime) {
    return mime != null && (mime.toLowerCase().contains("css"));
  }

  protected String getProxyUrl(String container) {
    JSONObject contentRewrite = config.getJsonObject(container, CONTENT_REWRITE_KEY);
    try {
      return contentRewrite.getString(PROXY_URL_KEY);
    } catch (JSONException e) {
      return null;
    }
  }

  protected String getConcatUrl(String container) {
    JSONObject contentRewrite = config.getJsonObject(container, CONTENT_REWRITE_KEY);
    try {
      return contentRewrite.getString(CONCAT_URL_KEY);
    } catch (JSONException e) {
      return null;
    }
  }

  protected LinkRewriter createLinkRewriter(GadgetSpec spec,
      ContentRewriterFeature rewriterFeature, String container) {
    return new ProxyingLinkRewriter(spec.getUrl(), rewriterFeature, getProxyUrl(container));
  }
}
