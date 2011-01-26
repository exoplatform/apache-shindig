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
import org.apache.shindig.gadgets.GadgetException;
import org.apache.shindig.gadgets.GadgetSpecFactory;
import org.apache.shindig.gadgets.http.HttpRequest;
import org.apache.shindig.gadgets.spec.GadgetSpec;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory for content rewriter features
 */
@Singleton
public class ContentRewriterFeatureFactory {

  private final GadgetSpecFactory specFactory;

//  private ContentRewriterFeature defaultFeature;

  private Map<String, ContentRewriterFeature> contentRewriters;

  static final String CONTENT_REWRITE_KEY = "gadgets.content-rewrite";
  static final String INCLUDE_TAGS_KEY = "include-tags";
  static final String INCLUDE_URLS_KEY = "include-urls";
  static final String EXCLUDE_TAGS_KEY = "exclude-urls";
  static final String EXPIRES_KEY = "expires";

  private final ContainerConfig config;

  @Inject
  public ContentRewriterFeatureFactory(
      GadgetSpecFactory specFactory,
      ContainerConfig config) {
    this.config = config;
    this.specFactory = specFactory;

    contentRewriters = new HashMap<String, ContentRewriterFeature>();
  }

  public ContentRewriterFeature getDefault(String container) {
    if (!contentRewriters.containsKey(container)) {
      contentRewriters.put(container, createContentRewriterFeature(null, container));
    }
    return contentRewriters.get(container);
  }

  public ContentRewriterFeature get(HttpRequest request) {
    Uri gadgetUri = request.getGadget();
    GadgetSpec spec;
    if (gadgetUri != null) {
      URI gadgetJavaUri = gadgetUri.toJavaUri();
      try {
        spec = specFactory.getGadgetSpec(gadgetJavaUri, false);
        if (spec != null) {
          return get(spec, request.getContainer());
        }
      } catch (GadgetException ge) { }
    }
    return getDefault(request.getContainer());
  }

  private ContentRewriterFeature createContentRewriterFeature(GadgetSpec spec, String container) {
    try {
    JSONObject contentRewrite = config.getJsonObject(container, CONTENT_REWRITE_KEY);

    JSONArray jsonTags = contentRewrite.getJSONArray(INCLUDE_TAGS_KEY);
    Set<String> tags = new HashSet<String>();
    for (int i = 0, j = jsonTags.length(); i < j; ++i) {
      tags.add(jsonTags.getString(i).toLowerCase());
    }

    return new ContentRewriterFeature(spec,
        contentRewrite.getString(INCLUDE_URLS_KEY),
        contentRewrite.getString(EXCLUDE_TAGS_KEY),
        contentRewrite.getString(EXPIRES_KEY),
        tags);
    } catch(JSONException e){
      return null;
    }
  }


  public ContentRewriterFeature get(GadgetSpec spec, String container) {
    ContentRewriterFeature rewriterFeature =
        (ContentRewriterFeature)spec.getAttribute("content-rewriter");
    if (rewriterFeature != null) return rewriterFeature;
    rewriterFeature = createContentRewriterFeature(spec,  container);
    spec.setAttribute("content-rewriter", rewriterFeature);
    return rewriterFeature;
  }
}
