/**
 * Copyright (C) 2013-2017 Expedia Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hotels.styx.routing.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.collect.ImmutableList;
import com.hotels.styx.api.HttpHandler2;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.infrastructure.configuration.yaml.JsonNodeConfig;
import com.hotels.styx.proxy.plugin.NamedPlugin;
import com.hotels.styx.routing.config.BuiltinHandlersFactory;
import com.hotels.styx.routing.config.BuiltinInterceptorsFactory;
import com.hotels.styx.routing.config.HttpHandlerFactory;
import com.hotels.styx.routing.config.RoutingConfigDefinition;
import com.hotels.styx.routing.config.RoutingConfigNode;
import com.hotels.styx.routing.config.RoutingConfigReference;
import rx.Observable;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.hotels.styx.routing.config.RoutingSupport.append;
import static com.hotels.styx.routing.config.RoutingSupport.missingAttributeError;
import static java.lang.String.join;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.StreamSupport.stream;

/**
 * A HTTP handler that contains HTTP interceptor pipeline.
 */
public class HttpInterceptorPipeline implements HttpHandler2 {
    private final StandardHttpPipeline handler;

    public HttpInterceptorPipeline(List<HttpInterceptor> interceptors, HttpHandler2 handler) {
        this.handler = new StandardHttpPipeline(interceptors, handler);
    }

    @Override
    public Observable<HttpResponse> handle(HttpRequest request, HttpInterceptor.Context context) {
        return handler.handle(request, context);
    }

    /**
     * An yaml config based builder for HttpInterceptorPipeline.
     */
    public static class ConfigFactory implements HttpHandlerFactory {
        private final Map<String, NamedPlugin> interceptors;
        private final BuiltinInterceptorsFactory interceptorFactory;

        public ConfigFactory(Supplier<Iterable<NamedPlugin>> interceptors, BuiltinInterceptorsFactory interceptorFactory) {
            this.interceptors = toMap(interceptors.get());
            this.interceptorFactory = interceptorFactory;
        }

        private static List<RoutingConfigNode> styxHttpPipeline(JsonNode pipeline) {
            return stream(pipeline.spliterator(), false)
                    .map(ConfigFactory::toRoutingConfigNode)
                    .collect(Collectors.toList());
        }

        private static RoutingConfigNode toRoutingConfigNode(JsonNode jsonNode) {
            if (jsonNode.getNodeType() == JsonNodeType.STRING) {
                return new RoutingConfigReference(jsonNode.asText());
            } else if (jsonNode.getNodeType() == JsonNodeType.OBJECT) {
                String name = ofNullable(jsonNode.get("name"))
                        .map(JsonNode::asText)
                        .orElse("");
                String type = checkNotNull(jsonNode.get("type").asText());
                JsonNode conf = jsonNode.get("config");
                return new RoutingConfigDefinition(name, type, conf);
            }
            throw new IllegalArgumentException("Invalid configuration. Expected a reference (string) or a configuration block.");
        }

        @Override
        public HttpHandler2 build(List<String> parents, BuiltinHandlersFactory builtinsFactory, RoutingConfigDefinition configBlock) {
            JsonNode pipeline = configBlock.config().get("pipeline");
            List<HttpInterceptor> interceptors = getHttpInterceptors(append(parents, "pipeline"), pipeline);

            RoutingConfigDefinition handlerConfig = new JsonNodeConfig(configBlock.config())
                    .get("handler", RoutingConfigDefinition.class)
                    .orElseThrow(() -> missingAttributeError(configBlock, join(".", parents), "handler"));

            return new HttpInterceptorPipeline(interceptors, builtinsFactory.build(append(parents, "handler"), handlerConfig));
        }

        private List<HttpInterceptor> getHttpInterceptors(List<String> parents, JsonNode pipeline) {
            if (pipeline == null || pipeline.isNull()) {
                return ImmutableList.of();
            }
            List<RoutingConfigNode> interceptorConfigs = styxHttpPipeline(pipeline);
            ensureValidPluginReferences(parents, interceptorConfigs);
            return interceptorConfigs.stream()
                    .map(node -> {
                        if (node instanceof RoutingConfigReference) {
                            String name = ((RoutingConfigReference) node).name();
                            return this.interceptors.get(name);
                        } else {
                            RoutingConfigDefinition block = (RoutingConfigDefinition) node;
                            return interceptorFactory.build(block);
                        }
                    })
                    .collect(Collectors.toList());
        }

        private void ensureValidPluginReferences(List<String> parents, List<RoutingConfigNode> interceptors) {
            interceptors.forEach(node -> {
                if (node instanceof RoutingConfigReference) {
                    String name = ((RoutingConfigReference) node).name();
                    if (!this.interceptors.containsKey(name)) {
                        throw new IllegalArgumentException(String.format("No such plugin or interceptor exists, attribute='%s', name='%s'",
                                join(".", parents), name));
                    }
                }
            });
        }

        private Map<String, NamedPlugin> toMap(Iterable<NamedPlugin> plugins) {
            return stream(plugins.spliterator(), false)
                    .collect(Collectors.toMap(NamedPlugin::name, identity()));
        }
    }

}
