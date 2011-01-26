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
package org.apache.shindig.gadgets.rewrite;

import org.apache.shindig.common.uri.Uri;
import org.apache.shindig.common.ContainerConfig;
import org.apache.shindig.gadgets.Gadget;
import org.apache.shindig.gadgets.http.HttpRequest;
import org.apache.shindig.gadgets.http.HttpResponse;
import org.json.JSONObject;
import org.json.JSONException;

import com.google.inject.Inject;
import com.google.inject.name.Named;

/**
 * Rewrite links to referenced content in stylsheets
 */
public class CSSContentRewriter implements ContentRewriter {

  private final ContentRewriterFeatureFactory rewriterFeatureFactory;

  private final ContainerConfig config;

  static final String CONTENT_REWRITE_KEY = "gadgets.content-rewrite";
  static final String PROXY_URL_KEY = "proxy-url";


  @Inject
  public CSSContentRewriter(ContentRewriterFeatureFactory rewriterFeatureFactory,
      ContainerConfig config) {
    this.rewriterFeatureFactory = rewriterFeatureFactory;
    this.config = config;
  }

  public RewriterResults rewrite(Gadget gadget, MutableContent content) {
    // Not supported
    return null;
  }

  public RewriterResults rewrite(HttpRequest request, HttpResponse original, MutableContent content) {
    if (!RewriterUtils.isCss(request, original)) {
      return null;      
    }
    ContentRewriterFeature feature = rewriterFeatureFactory.get(request);
    content.setContent(CssRewriter.rewrite(content.getContent(), request.getUri(),
        createLinkRewriter(request.getGadget(), feature, request.getContainer())));

    return RewriterResults.cacheableIndefinitely();
  }

  protected LinkRewriter createLinkRewriter(Uri gadgetUri, ContentRewriterFeature feature, String container) {
    JSONObject contentRewrite = config.getJsonObject(container, CONTENT_REWRITE_KEY);
    try {
      return new ProxyingLinkRewriter(gadgetUri, feature, contentRewrite.getString(PROXY_URL_KEY));
    } catch (JSONException e) {
      return null;
    }
  }
}

