/*
 * Copyright 2026 Ant Group Co., Ltd.
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
package org.secretflow.secretpad.web.utils;


import com.google.protobuf.ListValue;
import org.junit.jupiter.api.Test;
import org.secretflow.proto.pipeline.Pipeline;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.secretflow.secretpad.service.graph.ComponentTools;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ComponentToolsTest {


    @Test
    public void testGetHiddenPartyId_Normal() {
        Pipeline.NodeDef nodeDef = mock(Pipeline.NodeDef.class);
        List<Struct> attrsList = new ArrayList<>();
        Struct struct = mock(Struct.class);
        attrsList.add(struct);
        attrsList.add(struct);
        when(nodeDef.getAttrsList()).thenReturn(attrsList);

        Value value = mock(Value.class);
        ListValue listValue = mock(ListValue.class);
        when(struct.getFieldsOrDefault("ss", Value.newBuilder().build())).thenReturn(value);
        when(value.getListValue()).thenReturn(listValue);
        when(listValue.getValues(0)).thenReturn(value);
        when(value.getStringValue()).thenReturn("partyId");

        String result = ComponentTools.getHiddenPartyId(nodeDef);
        assertEquals("partyId", result);
    }

    @Test
    public void testGetHiddenPartyId_AttrsListEmpty() {
        Pipeline.NodeDef nodeDef = mock(Pipeline.NodeDef.class);
        when(nodeDef.getAttrsList()).thenReturn(new ArrayList<>());
        String result = ComponentTools.getHiddenPartyId(nodeDef);
        assertEquals("", result);
    }

}
