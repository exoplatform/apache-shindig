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

import org.apache.shindig.common.PropertiesModule;
import org.apache.shindig.common.ContainerConfig;
import org.apache.shindig.common.uri.Uri;
import org.apache.shindig.gadgets.EasyMockTestCase;
import org.apache.shindig.gadgets.Gadget;
import org.apache.shindig.gadgets.GadgetContext;
import org.apache.shindig.gadgets.GadgetException;
import org.apache.shindig.gadgets.http.HttpRequest;
import org.apache.shindig.gadgets.http.HttpResponse;
import org.apache.shindig.gadgets.http.HttpResponseBuilder;
import org.apache.shindig.gadgets.parse.GadgetHtmlParser;
import org.apache.shindig.gadgets.parse.ParseModule;
import org.apache.shindig.gadgets.spec.GadgetSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.common.collect.Maps;

import org.apache.commons.lang.StringUtils;

import java.net.URI;
import java.util.Set;
import java.util.Map;
import java.util.Collection;
import java.util.Arrays;

/**
 * Base class for testing content rewriting functionality
 */
public abstract class BaseRewriterTestCase extends EasyMockTestCase {
  public static final Uri SPEC_URL = Uri.parse("http://www.example.org/dir/g.xml");
  public static final String DEFAULT_PROXY_BASE = "http://www.test.com/dir/proxy?url=";
  public static final String DEFAULT_CONCAT_BASE = "http://www.test.com/dir/concat?";
  public static final String DEFAULT_CONTAINER = "shindig";

  protected Set<String> tags;
  protected ContentRewriterFeature defaultRewriterFeature;
  protected ContentRewriterFeatureFactory rewriterFeatureFactory;
  protected LinkRewriter defaultLinkRewriter;
  protected GadgetHtmlParser parser;
  protected Injector injector;
  protected HttpResponse fakeResponse;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    rewriterFeatureFactory = new ContentRewriterFeatureFactory(null, new FakeContainerConfig());
    defaultRewriterFeature = rewriterFeatureFactory.getDefault(DEFAULT_CONTAINER);
    tags = defaultRewriterFeature.getIncludedTags();
    defaultLinkRewriter = new ProxyingLinkRewriter(
        SPEC_URL,
        defaultRewriterFeature,
        DEFAULT_PROXY_BASE);
    injector = Guice.createInjector(new ParseModule(), new PropertiesModule());
    parser = injector.getInstance(GadgetHtmlParser.class);
    fakeResponse = new HttpResponseBuilder().setHeader("Content-Type", "unknown")
        .setResponse(new byte[]{ (byte)0xFE, (byte)0xFF}).create();
  }

  public static GadgetSpec createSpecWithRewrite(String include, String exclude, String expires,
      Set<String> tags) throws GadgetException {
    String xml = "<Module>" +
                 "<ModulePrefs title=\"title\">" +
                 "<Optional feature=\"content-rewrite\">\n" +
                 "      <Param name=\"expires\">" + expires + "</Param>\n" +
                 "      <Param name=\"include-urls\">" + include + "</Param>\n" +
                 "      <Param name=\"exclude-urls\">" + exclude + "</Param>\n" +
                 "      <Param name=\"include-tags\">" + StringUtils.join(tags, ",") + "</Param>\n" +
                 "</Optional>" +
                 "</ModulePrefs>" +
                 "<Content type=\"html\">Hello!</Content>" +
                 "</Module>";
    return new GadgetSpec(SPEC_URL, xml);
  }

  public static GadgetSpec createSpecWithoutRewrite() throws GadgetException {
    String xml = "<Module>" +
                 "<ModulePrefs title=\"title\">" +
                 "</ModulePrefs>" +
                 "<Content type=\"html\">Hello!</Content>" +
                 "</Module>";
    return new GadgetSpec(SPEC_URL, xml);
  }

  ContentRewriterFeatureFactory mockContentRewriterFeatureFactory(
      ContentRewriterFeature feature) {
    return new FakeRewriterFeatureFactory(feature);
  }

  String rewriteHelper(ContentRewriter rewriter, String s)
      throws Exception {
    MutableContent mc = rewriteContent(rewriter, s);
    String rewrittenContent = mc.getContent();

    // Strip around the HTML tags for convenience
    int htmlTagIndex = rewrittenContent.indexOf("<HTML>");
    if (htmlTagIndex != -1) {
      return rewrittenContent.substring(htmlTagIndex + 6,
          rewrittenContent.lastIndexOf("</HTML>"));
    }
    return rewrittenContent;
  }

  MutableContent rewriteContent(ContentRewriter rewriter, String s)
      throws Exception {
    MutableContent mc = new MutableContent(parser, s);

    GadgetSpec spec = new GadgetSpec(SPEC_URL,
        "<Module><ModulePrefs title=''/><Content><![CDATA[" + s + "]]></Content></Module>");

    GadgetContext context = new GadgetContext() {
      @Override
      public URI getUrl() {
        return SPEC_URL.toJavaUri();
      }
    };

    Gadget gadget = new Gadget()
        .setContext(context)
        .setSpec(spec);
    rewriter.rewrite(gadget, mc);
    return mc;
  }

  private static class FakeRewriterFeatureFactory extends ContentRewriterFeatureFactory {
    private final ContentRewriterFeature feature;

    public FakeRewriterFeatureFactory(ContentRewriterFeature feature) {
      super(null, new FakeContainerConfig());
      this.feature = feature;
    }

    @Override
    public ContentRewriterFeature get(GadgetSpec spec, String container) {
      return feature;
    }

    @Override
    public ContentRewriterFeature get(HttpRequest request) {
      return feature;
    }
  }

  protected static class FakeContainerConfig implements ContainerConfig {
    private final Map<String, String> properties = Maps.newHashMap();

    public String get(String container, String property) {
      return properties.get(property);
    }

    public Collection<String> getContainers() {
      return null;
    }

    public Object getJson(String container, String parameter) {
      return null;
    }

    public JSONArray getJsonArray(String container, String parameter) {
      return null;
    }

    public JSONObject getJsonObject(String container, String parameter) {
      if (parameter.equals("gadgets.content-rewrite")) {
        try {
        JSONObject jsonObject = new JSONObject();
        JSONArray tags = new JSONArray();
        tags.put("embed");
        tags.put("img");
        tags.put("script");
        tags.put("link");
        tags.put("style");
        jsonObject.put("include-tags", tags);
        jsonObject.put("include-urls", ".*");
        jsonObject.put("exclude-urls", "");
        jsonObject.put("expires", "HTTP");
        jsonObject.put("proxy-url", "http://www.test.com/dir/proxy?url=");
        jsonObject.put("concat-url", "http://www.test.com/dir/concat?");
        return jsonObject;
        } catch (JSONException e) {}
      }
      return null;
    }
  }
}
