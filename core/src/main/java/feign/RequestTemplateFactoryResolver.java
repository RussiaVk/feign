/*
 * Copyright 2012-2023 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.template.UriUtils;
import feign.utils.BeanEvaluator;
import feign.utils.RecComponent;
import feign.utils.RecordEvaluator;
import feign.utils.RecordInvokeUtils;

final class RequestTemplateFactoryResolver {
  private final Encoder encoder;
  private final QueryMapEncoder queryMapEncoder;

  RequestTemplateFactoryResolver(Encoder encoder, QueryMapEncoder queryMapEncoder) {
    this.encoder = checkNotNull(encoder, "encoder");
    this.queryMapEncoder = checkNotNull(queryMapEncoder, "queryMapEncoder");
  }

  public RequestTemplate.Factory resolve(Target<?> target, MethodMetadata md) {


    if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
      return new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
    } else if (md.bodyIndex() != null || md.alwaysEncodeBody()) {
      return new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
    } else {
      return new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
    }
  }

  private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

    private final QueryMapEncoder queryMapEncoder;

    protected final MethodMetadata metadata;
    protected final Target<?> target;
    private final Map<Integer, Param.Expander> indexToExpander =
        new LinkedHashMap<Integer, Param.Expander>();

    @SuppressWarnings("deprecation")
    private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder,
        Target<?> target) {

      this.metadata = metadata;
      this.target = target;
      this.queryMapEncoder = queryMapEncoder;
      if (metadata.indexToExpander() != null) {
        indexToExpander.putAll(metadata.indexToExpander());
        return;
      }
      if (metadata.indexToExpanderClass().isEmpty()) {
        return;
      }
      for (Map.Entry<Integer, Class<? extends Param.Expander>> indexToExpanderClass : metadata
          .indexToExpanderClass().entrySet()) {
        try {
          indexToExpander.put(indexToExpanderClass.getKey(),
              indexToExpanderClass.getValue().newInstance());
        } catch (InstantiationException e) {
          throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    @Experimental
    private Object[] expand(Object[] argv) {
      List<Object> list = new ArrayList<>();
      boolean isSupportRecord = RecordEvaluator.isSupport();
      Set<Integer> indexToExpand = metadata.indexToExpand();
      int index = 0;
      for (Object i : argv) {
        boolean expand = indexToExpand.contains(index);

        if (i == null) {
          list.add(i);
        } else if (expand && isSupportRecord && RecordInvokeUtils.isRecord(i.getClass())) {

          RecComponent[] arr = RecordInvokeUtils.recordComponents(i.getClass(), null);
          for (RecComponent r : arr) {
            Object value = RecordInvokeUtils.componentValue(i, r);
            list.add(value);
          }
        } else if (expand && BeanEvaluator.isBean(i.getClass())) {
          Field[] aggregatedParams = Arrays.stream(i.getClass().getDeclaredFields())
              .filter(v -> !"this$0".equals(v.getName()))
              .toArray(Field[]::new);
          for (Field aggregatedParam : aggregatedParams) {
            try {
              aggregatedParam.setAccessible(true);
              Object value = aggregatedParam.get(i);
              list.add(value);
            } catch (IllegalArgumentException | IllegalAccessException e) {
              e.printStackTrace();
            }
          }
        } else {
          list.add(i);
        }
        index++;
      }
      return list.toArray();
    }

    @Override
    public RequestTemplate create(Object[] argv) {
      RequestTemplate mutable = RequestTemplate.from(metadata.template());
      mutable.feignTarget(target);
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        mutable.target(String.valueOf(argv[urlIndex]));
      }
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      if (argv != null && !metadata.indexToExpand().isEmpty()) {

        argv = expand(argv);
      }
      for (Map.Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        int i = entry.getKey();
        Object value = argv[entry.getKey()];
        if (indexToExpander.containsKey(i)) {
          value = expandElements(indexToExpander.get(i), value);
        }
        for (String name : entry.getValue()) {
          varBuilder.put(name, value);
        }
      }

      RequestTemplate template = resolve(argv, mutable, varBuilder);
      if (metadata.queryMapIndex() != null) {
        // add query map parameters after initial resolve so that they take
        // precedence over any predefined values
        Object value = argv[metadata.queryMapIndex()];

        Map<String, Object> queryMap = toQueryMap(value);
        template = addQueryMapQueryParameters(queryMap, template);
      }

      if (metadata.headerMapIndex() != null) {
        // add header map parameters for a resolution of the user pojo object
        Object value = argv[metadata.headerMapIndex()];
        Map<String, Object> headerMap = toQueryMap(value);
        template = addHeaderMapHeaders(headerMap, template);
      }
      return template;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toQueryMap(Object value) {
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
      try {
        return queryMapEncoder.encode(value);
      } catch (EncodeException e) {
        throw new IllegalStateException(e);
      }
    }

    private Object expandElements(Param.Expander expander, Object value) {
      if (value instanceof Iterable) {
        return expandIterable(expander, (Iterable<?>) value);
      }
      return expander.expand(value);
    }

    private List<String> expandIterable(Param.Expander expander, Iterable<?> value) {
      List<String> values = new ArrayList<String>();
      for (Object element : value) {
        if (element != null) {
          values.add(expander.expand(element));
        }
      }
      return values;
    }

    private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                RequestTemplate mutable) {
      for (Map.Entry<String, Object> currEntry : headerMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : nextObject.toString());
          }
        } else {
          values.add(currValue == null ? null : currValue.toString());
        }

        mutable.header(currEntry.getKey(), values);
      }
      return mutable;
    }

    private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                       RequestTemplate mutable) {
      for (Map.Entry<String, Object> currEntry : queryMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {

          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : UriUtils.encode(nextObject.toString()));
          }
        } else if (currValue instanceof Object[]) {
          for (Object value : (Object[]) currValue) {
            values.add(value == null ? null : UriUtils.encode(value.toString()));
          }
        } else {
          if (currValue != null) {
            values.add(UriUtils.encode(currValue.toString()));
          }
        }

        if (values.size() > 0) {
          mutable.query(UriUtils.encode(currEntry.getKey()), values);
        }
      }
      return mutable;
    }

    @SuppressWarnings("deprecation")
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      Set<String> variableToEncoded = new HashSet<>();
      for (Map.Entry<Integer, Boolean> entry : metadata.indexToEncoded().entrySet()) {
        Collection<String> names = metadata.indexToName().get(entry.getKey());
        for (String name : names)
          variableToEncoded.add(name);
      }
      return mutable.resolve(variables, variableToEncoded);
    }
  }

  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder, Target<?> target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Map.Entry<String, Object> entry : variables.entrySet()) {
        if (metadata.formParams().contains(entry.getKey())) {
          formVariables.put(entry.getKey(), entry.getValue());
        }
      }
      try {
        if (!metadata.indexToEncoded().isEmpty()) {
          Set<String> set = metadata.indexToEncoded().keySet().stream().map(
              v -> metadata.indexToName().get(v)).flatMap(Collection::stream)
              .collect(Collectors.toSet());
          mutable.setAlreadyEncoded(set);
        }
        encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }

  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder,
        Target<?> target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {

      boolean alwaysEncodeBody = mutable.methodMetadata().alwaysEncodeBody();

      Object body = null;
      if (!alwaysEncodeBody) {
        body = argv[metadata.bodyIndex()];
        checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      }

      try {
        if (alwaysEncodeBody) {
          body = argv == null ? new Object[0] : argv;
          encoder.encode(body, Object[].class, mutable);
        } else {
          encoder.encode(body, metadata.bodyType(), mutable);
        }
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }

      return super.resolve(argv, mutable, variables);
    }
  }
}
