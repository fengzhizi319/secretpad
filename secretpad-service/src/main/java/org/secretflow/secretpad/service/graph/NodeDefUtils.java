/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.service.graph;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.secretflow.spec.v1.Attribute;
import com.secretflow.spec.v1.NodeEvalParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.secretflow.proto.pipeline.Pipeline;

import java.util.List;

/**
 * Utilities for converting the legacy {@link Pipeline.NodeDef} (used internally by SecretPad)
 * to the new {@link NodeEvalParam} format expected by SecretFlow >= 1.15.
 *
 * <p>SecretFlow 1.15 replaced the separate {@code domain}/{@code name}/{@code version}
 * fields with a single {@code comp_id} in the form {@code domain/name:version}.
 * Attribute values are also represented as {@link Attribute} instead of
 * {@link Struct}.</p>
 */
@Slf4j
public final class NodeDefUtils {

    private NodeDefUtils() {
    }

    /**
     * Convert a {@link Pipeline.NodeDef} to {@link NodeEvalParam}.
     *
     * @param nodeDef the legacy node definition
     * @return the NodeEvalParam used by SecretFlow
     */
    public static NodeEvalParam toNodeEvalParam(Pipeline.NodeDef nodeDef) {
        if (nodeDef == null) {
            return NodeEvalParam.getDefaultInstance();
        }

        String domain = nodeDef.getDomain();
        String name = nodeDef.getName();
        String version = StringUtils.isBlank(nodeDef.getVersion()) ? "0.0.1" : nodeDef.getVersion();
        String compId = StringUtils.isBlank(domain) || StringUtils.isBlank(name)
                ? ""
                : domain + "/" + name + ":" + version;

        NodeEvalParam.Builder builder = NodeEvalParam.newBuilder()
                .setVersion(version)
                .setCompId(compId)
                .addAllAttrPaths(nodeDef.getAttrPathsList())
                .addAllInputs(nodeDef.getInputsList())
                .addAllOutputUris(nodeDef.getOutputUrisList())
                .setCheckpointUri(nodeDef.getCheckpointUri());

        for (Struct attrStruct : nodeDef.getAttrsList()) {
            builder.addAttrs(convertStructToAttribute(attrStruct));
        }

        return builder.build();
    }

    /**
     * Convert a {@link Struct} representing a component attribute into an
     * {@link Attribute} protobuf message.
     *
     * <p>The struct is expected to contain at most one typed value among
     * {@code s}/{@code f}/{@code i64}/{@code b}/{@code ss}/{@code fs}/{@code i64s}/{@code bs}
     * plus an optional {@code is_na} flag.</p>
     */
    private static Attribute convertStructToAttribute(Struct attrStruct) {
        Attribute.Builder attrBuilder = Attribute.newBuilder();
        for (java.util.Map.Entry<String, Value> entry : attrStruct.getFieldsMap().entrySet()) {
            String key = entry.getKey();
            Value value = entry.getValue();
            switch (key) {
                case "s" -> attrBuilder.setS(value.getStringValue());
                case "f" -> attrBuilder.setF((float) value.getNumberValue());
                case "i64" -> attrBuilder.setI64((long) value.getNumberValue());
                case "b" -> attrBuilder.setB(value.getBoolValue());
                case "ss" -> attrBuilder.addAllSs(extractStringList(value.getListValue()));
                case "fs" -> attrBuilder.addAllFs(extractFloatList(value.getListValue()));
                case "i64s" -> attrBuilder.addAllI64S(extractInt64List(value.getListValue()));
                case "bs" -> attrBuilder.addAllBs(extractBoolList(value.getListValue()));
                case "is_na" -> attrBuilder.setIsNa(value.getBoolValue());
                default -> log.warn("Unknown attribute key '{}', skipping", key);
            }
        }
        return attrBuilder.build();
    }

    private static List<String> extractStringList(ListValue listValue) {
        return listValue.getValuesList().stream()
                .map(Value::getStringValue)
                .toList();
    }

    private static List<Float> extractFloatList(ListValue listValue) {
        return listValue.getValuesList().stream()
                .map(v -> (float) v.getNumberValue())
                .toList();
    }

    private static List<Long> extractInt64List(ListValue listValue) {
        return listValue.getValuesList().stream()
                .map(v -> (long) v.getNumberValue())
                .toList();
    }

    private static List<Boolean> extractBoolList(ListValue listValue) {
        return listValue.getValuesList().stream()
                .map(Value::getBoolValue)
                .toList();
    }
}
