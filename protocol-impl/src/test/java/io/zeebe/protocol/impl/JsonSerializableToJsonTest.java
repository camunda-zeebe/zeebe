/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.protocol.impl;

import static io.zeebe.util.buffer.BufferUtil.wrapArray;
import static io.zeebe.util.buffer.BufferUtil.wrapString;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.protocol.impl.encoding.MsgPackConverter;
import io.zeebe.protocol.impl.record.CopiedRecord;
import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.impl.record.UnifiedRecordValue;
import io.zeebe.protocol.impl.record.VersionInfo;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentDistributionRecord;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.error.ErrorRecord;
import io.zeebe.protocol.impl.record.value.incident.IncidentRecord;
import io.zeebe.protocol.impl.record.value.job.JobBatchRecord;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.impl.record.value.message.MessageRecord;
import io.zeebe.protocol.impl.record.value.message.MessageStartEventSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.message.MessageSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.message.ProcessInstanceSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceCreationRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.impl.record.value.variable.VariableDocumentRecord;
import io.zeebe.protocol.impl.record.value.variable.VariableRecord;
import io.zeebe.protocol.record.JsonSerializable;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.protocol.record.value.VariableDocumentUpdateSemantic;
import io.zeebe.protocol.record.value.deployment.ResourceType;
import io.zeebe.test.util.JsonUtil;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class JsonSerializableToJsonTest {

  private static final String VARIABLES_JSON = "{'foo':'bar'}";
  private static final DirectBuffer VARIABLES_MSGPACK =
      new UnsafeBuffer(MsgPackConverter.convertToMsgPack(VARIABLES_JSON));

  private static final RuntimeException RUNTIME_EXCEPTION = new RuntimeException("test");

  private static final String STACK_TRACE;

  static {
    final StringWriter stringWriter = new StringWriter();
    final PrintWriter pw = new PrintWriter(stringWriter);
    RUNTIME_EXCEPTION.printStackTrace(pw);

    STACK_TRACE = stringWriter.toString();
  }

  @Parameter public String testName;

  @Parameter(1)
  public Supplier<JsonSerializable> actualRecordSupplier;

  @Parameter(2)
  public String expectedJson;

  @Parameters(name = "{index}: {0}")
  public static Object[][] records() {
    return new Object[][] {
      /////////////////////////////////////////////////////////////////////////////////////////////
      //////////////////////////////////////// Record /////////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "Record",
        (Supplier<JsonSerializable>)
            () -> {
              final RecordMetadata recordMetadata = new RecordMetadata();

              final DeploymentIntent intent = DeploymentIntent.CREATE;
              final int protocolVersion = 1;
              final VersionInfo brokerVersion = new VersionInfo(1, 2, 3);
              final ValueType valueType = ValueType.DEPLOYMENT;

              final RecordType recordType = RecordType.COMMAND;
              final String rejectionReason = "fails";
              final RejectionType rejectionType = RejectionType.INVALID_ARGUMENT;
              final int requestId = 23;
              final int requestStreamId = 1;

              recordMetadata
                  .intent(intent)
                  .protocolVersion(protocolVersion)
                  .brokerVersion(brokerVersion)
                  .valueType(valueType)
                  .recordType(recordType)
                  .rejectionReason(rejectionReason)
                  .rejectionType(rejectionType)
                  .requestId(requestId)
                  .requestStreamId(requestStreamId);

              final String resourceName = "resource";
              final ResourceType resourceType = ResourceType.BPMN_XML;
              final DirectBuffer resource = wrapString("contents");
              final String bpmnProcessId = "testProcess";
              final long processDefinitionKey = 123;
              final int processVersion = 12;
              final DirectBuffer checksum = wrapString("checksum");

              final DeploymentRecord record = new DeploymentRecord();
              record
                  .resources()
                  .add()
                  .setResourceName(wrapString(resourceName))
                  .setResourceType(resourceType)
                  .setResource(resource);
              record
                  .processes()
                  .add()
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setKey(processDefinitionKey)
                  .setResourceName(wrapString(resourceName))
                  .setVersion(processVersion)
                  .setChecksum(checksum)
                  .setResource(resource);

              final int key = 1234;
              final int position = 4321;
              final int sourcePosition = 231;
              final long timestamp = 2191L;

              return new CopiedRecord<>(
                  record, recordMetadata, key, 0, position, sourcePosition, timestamp);
            },
        "{'valueType':'DEPLOYMENT','key':1234,'position':4321,'timestamp':2191,'recordType':'COMMAND','intent':'CREATE','partitionId':0,'rejectionType':'INVALID_ARGUMENT','rejectionReason':'fails','brokerVersion':'1.2.3','sourceRecordPosition':231,'value':{'deployedProcesses':[{'resource':'Y29udGVudHM=','version':12,'bpmnProcessId':'testProcess','resourceName':'resource','checksum':'Y2hlY2tzdW0=','processDefinitionKey':123}],'resources':[{'resourceName':'resource','resourceType':'BPMN_XML','resource':'Y29udGVudHM='}]}}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      //////////////////////////////////// DeploymentRecord ///////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "DeploymentRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String resourceName = "resource";
              final ResourceType resourceType = ResourceType.BPMN_XML;
              final DirectBuffer resource = wrapString("contents");
              final String bpmnProcessId = "testProcess";
              final long processDefinitionKey = 123;
              final int processVersion = 12;
              final DirectBuffer checksum = wrapString("checksum");
              final DeploymentRecord record = new DeploymentRecord();
              record
                  .resources()
                  .add()
                  .setResourceName(wrapString(resourceName))
                  .setResourceType(resourceType)
                  .setResource(resource);
              record
                  .processes()
                  .add()
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setKey(processDefinitionKey)
                  .setResourceName(wrapString(resourceName))
                  .setVersion(processVersion)
                  .setChecksum(checksum)
                  .setResource(resource);
              return record;
            },
        "{'resources':[{'resourceType':'BPMN_XML','resourceName':'resource','resource':'Y29udGVudHM='}],'deployedProcesses':[{'resource':'Y29udGVudHM=','checksum':'Y2hlY2tzdW0=','bpmnProcessId':'testProcess','version':12,'processDefinitionKey':123,'resourceName':'resource'}]}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ////////////////////////////// DeploymentDistributionRecord /////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "DeploymentDistributionRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final var record = new DeploymentDistributionRecord();
              record.setPartition(2);
              return record;
            },
        "{'partitionId':2}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      //////////////////////////////////// Empty DeploymentRecord /////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "Empty DeploymentRecord",
        (Supplier<UnifiedRecordValue>) DeploymentRecord::new,
        "{'resources':[],'deployedProcesses':[]}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////////// ErrorRecord ///////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "ErrorRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final ErrorRecord record = new ErrorRecord();
              record.initErrorRecord(RUNTIME_EXCEPTION, 123);
              record.setProcessInstanceKey(4321);
              return record;
            },
        errorRecordAsJson(4321, STACK_TRACE)
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////////// Empty ErrorRecord /////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "Empty ErrorRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final ErrorRecord record = new ErrorRecord();
              record.initErrorRecord(RUNTIME_EXCEPTION, 123);
              return record;
            },
        errorRecordAsJson(-1, STACK_TRACE)
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      //////////////////////////////////// IncidentRecord /////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "IncidentRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final long elementInstanceKey = 34;
              final long processDefinitionKey = 134;
              final long processInstanceKey = 10;
              final String elementId = "activity";
              final String bpmnProcessId = "process";
              final String errorMessage = "error";
              final ErrorType errorType = ErrorType.IO_MAPPING_ERROR;
              final long jobKey = 123;

              return new IncidentRecord()
                  .setElementInstanceKey(elementInstanceKey)
                  .setProcessDefinitionKey(processDefinitionKey)
                  .setProcessInstanceKey(processInstanceKey)
                  .setElementId(wrapString(elementId))
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setErrorMessage(errorMessage)
                  .setErrorType(errorType)
                  .setJobKey(jobKey)
                  .setVariableScopeKey(elementInstanceKey);
            },
        "{'errorType':'IO_MAPPING_ERROR','errorMessage':'error','bpmnProcessId':'process','processDefinitionKey':134,'processInstanceKey':10,'elementId':'activity','elementInstanceKey':34,'jobKey':123,'variableScopeKey':34}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      //////////////////////////////////// Empty IncidentRecord ///////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      new Object[] {
        "Empty IncidentRecord",
        (Supplier<UnifiedRecordValue>) IncidentRecord::new,
        "{'errorType':'UNKNOWN','errorMessage':'','bpmnProcessId':'','processDefinitionKey':-1,'processInstanceKey':-1,'elementId':'','elementInstanceKey':-1,'jobKey':-1,'variableScopeKey':-1}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// JobBatchRecord ////////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "JobBatchRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final int amount = 1;
              final long timeout = 2L;
              final String type = "type";
              final String worker = "worker";

              final JobBatchRecord record =
                  new JobBatchRecord()
                      .setMaxJobsToActivate(amount)
                      .setTimeout(timeout)
                      .setType(type)
                      .setWorker(worker)
                      .setTruncated(true);

              record.jobKeys().add().setValue(3L);
              final JobRecord jobRecord = record.jobs().add();

              final String bpmnProcessId = "test-process";
              final int processDefinitionKey = 13;
              final int processDefinitionVersion = 12;
              final int processInstanceKey = 1234;
              final String activityId = "activity";
              final int activityInstanceKey = 123;

              jobRecord
                  .setWorker(wrapString(worker))
                  .setType(wrapString(type))
                  .setVariables(VARIABLES_MSGPACK)
                  .setRetries(3)
                  .setErrorMessage("failed message")
                  .setErrorCode(wrapString("error"))
                  .setDeadline(1000L)
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setProcessDefinitionKey(processDefinitionKey)
                  .setProcessDefinitionVersion(processDefinitionVersion)
                  .setProcessInstanceKey(processInstanceKey)
                  .setElementId(wrapString(activityId))
                  .setElementInstanceKey(activityInstanceKey);

              return record;
            },
        "{'maxJobsToActivate':1,'type':'type','worker':'worker','truncated':true,'jobKeys':[3],'jobs':[{'bpmnProcessId':'test-process','processDefinitionKey':13,'processDefinitionVersion':12,'processInstanceKey':1234,'elementId':'activity','elementInstanceKey':123,'type':'type','worker':'worker','variables':{'foo':'bar'},'retries':3,'errorMessage':'failed message','errorCode':'error','customHeaders':{},'deadline':1000}],'timeout':2}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// Empty JobBatchRecord //////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty JobBatchRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String type = "type";
              return new JobBatchRecord().setType(type);
            },
        "{'worker':'','type':'type','maxJobsToActivate':-1,'truncated':false,'jobKeys':[],'jobs':[],'timeout':-1}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////////// JobRecord /////////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "JobRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String worker = "myWorker";
              final String type = "myType";
              final int retries = 12;
              final int deadline = 13;

              final String bpmnProcessId = "test-process";
              final int processDefinitionKey = 13;
              final int processDefinitionVersion = 12;
              final int processInstanceKey = 1234;
              final String elementId = "activity";
              final int activityInstanceKey = 123;

              final Map<String, String> customHeaders =
                  Collections.singletonMap("workerVersion", "42");

              final JobRecord record =
                  new JobRecord()
                      .setWorker(wrapString(worker))
                      .setType(wrapString(type))
                      .setVariables(VARIABLES_MSGPACK)
                      .setRetries(retries)
                      .setDeadline(deadline)
                      .setErrorMessage("failed message")
                      .setErrorCode(wrapString("error"))
                      .setBpmnProcessId(wrapString(bpmnProcessId))
                      .setProcessDefinitionKey(processDefinitionKey)
                      .setProcessDefinitionVersion(processDefinitionVersion)
                      .setProcessInstanceKey(processInstanceKey)
                      .setElementId(wrapString(elementId))
                      .setElementInstanceKey(activityInstanceKey);

              record.setCustomHeaders(wrapArray(MsgPackConverter.convertToMsgPack(customHeaders)));
              return record;
            },
        "{'bpmnProcessId':'test-process','processDefinitionKey':13,'processDefinitionVersion':12,'processInstanceKey':1234,'elementId':'activity','elementInstanceKey':123,'worker':'myWorker','type':'myType','variables':{'foo':'bar'},'retries':12,'errorMessage':'failed message','errorCode':'error','customHeaders':{'workerVersion':'42'},'deadline':13}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////////// Empty JobRecord ///////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty JobRecord",
        (Supplier<UnifiedRecordValue>) JobRecord::new,
        "{'type':'','processDefinitionVersion':-1,'elementId':'','bpmnProcessId':'','processDefinitionKey':-1,'processInstanceKey':-1,'elementInstanceKey':-1,'variables':{},'worker':'','retries':-1,'errorMessage':'','errorCode':'','customHeaders':{},'deadline':-1}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// MessageRecord /////////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "MessageRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String correlationKey = "test-key";
              final String messageName = "test-message";
              final long timeToLive = 12;
              final String messageId = "test-id";

              return new MessageRecord()
                  .setCorrelationKey(wrapString(correlationKey))
                  .setName(wrapString(messageName))
                  .setVariables(VARIABLES_MSGPACK)
                  .setTimeToLive(timeToLive)
                  .setDeadline(22L)
                  .setMessageId(wrapString(messageId));
            },
        "{'timeToLive':12,'correlationKey':'test-key','variables':{'foo':'bar'},'messageId':'test-id','name':'test-message','deadline':22}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// Empty MessageRecord ///////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty MessageRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String correlationKey = "test-key";
              final String messageName = "test-message";
              final long timeToLive = 12;

              return new MessageRecord()
                  .setTimeToLive(timeToLive)
                  .setCorrelationKey(correlationKey)
                  .setName(messageName);
            },
        "{'timeToLive':12,'correlationKey':'test-key','variables':{},'messageId':'','name':'test-message','deadline':-1}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// MessageStartEventSubscriptionRecord ///////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "MessageStartEventSubscriptionRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String messageName = "name";
              final String startEventId = "startEvent";
              final int processDefinitionKey = 22334;
              final String bpmnProcessId = "process";

              return new MessageStartEventSubscriptionRecord()
                  .setMessageName(wrapString(messageName))
                  .setStartEventId(wrapString(startEventId))
                  .setProcessDefinitionKey(processDefinitionKey)
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setProcessInstanceKey(2L)
                  .setMessageKey(3L)
                  .setCorrelationKey(wrapString("test-key"))
                  .setVariables(VARIABLES_MSGPACK);
            },
        "{'processDefinitionKey':22334,'messageName':'name','startEventId':'startEvent','bpmnProcessId':'process','processInstanceKey':2,'messageKey':3,'correlationKey':'test-key','variables':{'foo':'bar'}}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// Empty MessageStartEventSubscriptionRecord /////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty MessageStartEventSubscriptionRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final int processDefinitionKey = 22334;

              return new MessageStartEventSubscriptionRecord()
                  .setProcessDefinitionKey(processDefinitionKey);
            },
        "{'processDefinitionKey':22334,'messageName':'','startEventId':'','bpmnProcessId':'','processInstanceKey':-1,'messageKey':-1,'correlationKey':'','variables':{}}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// MessageSubscriptionRecord /////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "MessageSubscriptionRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final long elementInstanceKey = 1L;
              final String bpmnProcessId = "process";
              final String messageName = "name";
              final long processInstanceKey = 2L;
              final String correlationKey = "key";
              final long messageKey = 3L;

              return new MessageSubscriptionRecord()
                  .setElementInstanceKey(elementInstanceKey)
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setMessageKey(messageKey)
                  .setMessageName(wrapString(messageName))
                  .setProcessInstanceKey(processInstanceKey)
                  .setCorrelationKey(wrapString(correlationKey))
                  .setVariables(VARIABLES_MSGPACK);
            },
        "{'processInstanceKey':2,'elementInstanceKey':1,'messageName':'name','correlationKey':'key','bpmnProcessId':'process','messageKey':3,'variables':{'foo':'bar'},'interrupting':true}"
      },
      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// Empty MessageSubscriptionRecord
      // /////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty MessageSubscriptionRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final long elementInstanceKey = 13L;
              final long processInstanceKey = 1L;

              return new MessageSubscriptionRecord()
                  .setProcessInstanceKey(processInstanceKey)
                  .setElementInstanceKey(elementInstanceKey);
            },
        "{'processInstanceKey':1,'elementInstanceKey':13,'messageName':'','correlationKey':'','bpmnProcessId':'','messageKey':-1,'variables':{},'interrupting':true}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      //////////////////////////// ProcessInstanceSubscriptionRecord /////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "ProcessInstanceSubscriptionRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final long elementInstanceKey = 123;
              final String bpmnProcessId = "process";
              final String messageName = "test-message";
              final int subscriptionPartitionId = 2;
              final int messageKey = 3;
              final long processInstanceKey = 1345;
              final String correlationKey = "key";

              return new ProcessInstanceSubscriptionRecord()
                  .setElementInstanceKey(elementInstanceKey)
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setMessageName(wrapString(messageName))
                  .setMessageKey(messageKey)
                  .setSubscriptionPartitionId(subscriptionPartitionId)
                  .setProcessInstanceKey(processInstanceKey)
                  .setVariables(VARIABLES_MSGPACK)
                  .setCorrelationKey(wrapString(correlationKey))
                  .setElementId(wrapString("A"));
            },
        "{'elementInstanceKey':123,'messageName':'test-message','processInstanceKey':1345,'variables':{'foo':'bar'},'bpmnProcessId':'process','messageKey':3,'correlationKey':'key','elementId':'A','interrupting':true}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      //////////////////////////// Empty ProcessInstanceSubscriptionRecord ///////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty ProcessInstanceSubscriptionRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final long elementInstanceKey = 123;
              final long processInstanceKey = 1345;

              return new ProcessInstanceSubscriptionRecord()
                  .setProcessInstanceKey(processInstanceKey)
                  .setElementInstanceKey(elementInstanceKey);
            },
        "{'elementInstanceKey':123,'messageName':'','processInstanceKey':1345,'variables':{},'bpmnProcessId':'','messageKey':-1,'correlationKey':'','elementId':'','interrupting':true}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      /////////////////////////////////// TimerRecord /////////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "TimerRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final int processDefinitionKey = 13;
              final int processInstanceKey = 1234;
              final int dueDate = 1234;
              final int elementInstanceKey = 567;
              final String handlerNodeId = "node1";
              final int repetitions = 3;

              return new TimerRecord()
                  .setDueDate(dueDate)
                  .setElementInstanceKey(elementInstanceKey)
                  .setTargetElementId(wrapString(handlerNodeId))
                  .setRepetitions(repetitions)
                  .setProcessInstanceKey(processInstanceKey)
                  .setProcessDefinitionKey(processDefinitionKey);
            },
        "{'elementInstanceKey':567,'processInstanceKey':1234,'dueDate':1234,'targetElementId':'node1','repetitions':3,'processDefinitionKey':13}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// VariableRecord ////////////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "VariableRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String name = "x";
              final String value = "1";
              final long scopeKey = 3;
              final long processInstanceKey = 2;
              final long processDefinitionKey = 4;

              return new VariableRecord()
                  .setName(wrapString(name))
                  .setValue(new UnsafeBuffer(MsgPackConverter.convertToMsgPack(value)))
                  .setScopeKey(scopeKey)
                  .setProcessInstanceKey(processInstanceKey)
                  .setProcessDefinitionKey(processDefinitionKey);
            },
        "{'scopeKey':3,'processInstanceKey':2,'processDefinitionKey':4,'name':'x','value':'1'}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// VariableDocumentRecord ////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "VariableDocumentRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String value = "{'foo':1}";
              final long scopeKey = 3;

              return new VariableDocumentRecord()
                  .setUpdateSemantics(VariableDocumentUpdateSemantic.LOCAL)
                  .setVariables(new UnsafeBuffer(MsgPackConverter.convertToMsgPack(value)))
                  .setScopeKey(scopeKey);
            },
        "{'updateSemantics':'LOCAL','variables':{'foo':1},'scopeKey':3}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// Empty VariableDocumentRecord //////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty VariableDocumentRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final long scopeKey = 3;

              return new VariableDocumentRecord().setScopeKey(scopeKey);
            },
        "{'updateSemantics':'PROPAGATE','variables':{},'scopeKey':3}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// ProcessInstanceCreationRecord ////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "ProcessInstanceCreationRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String processId = "process";
              final long key = 1L;
              final int version = 1;
              final long instanceKey = 2L;

              return new ProcessInstanceCreationRecord()
                  .setBpmnProcessId(processId)
                  .setProcessDefinitionKey(key)
                  .setVersion(version)
                  .setVariables(
                      new UnsafeBuffer(
                          MsgPackConverter.convertToMsgPack("{'foo':'bar','baz':'boz'}")))
                  .setProcessInstanceKey(instanceKey);
            },
        "{'variables':{'foo':'bar','baz':'boz'},'bpmnProcessId':'process','processDefinitionKey':1,'version':1,'processInstanceKey':2}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// Empty ProcessInstanceCreationRecord //////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty ProcessInstanceCreationRecord",
        (Supplier<UnifiedRecordValue>) ProcessInstanceCreationRecord::new,
        "{'variables':{},'bpmnProcessId':'','processDefinitionKey':-1,'version':-1,'processInstanceKey':-1}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// ProcessInstanceRecord ////////////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "ProcessInstanceRecord",
        (Supplier<UnifiedRecordValue>)
            () -> {
              final String bpmnProcessId = "test-process";
              final int processDefinitionKey = 13;
              final int version = 12;
              final int processInstanceKey = 1234;
              final String elementId = "activity";
              final int flowScopeKey = 123;
              final BpmnElementType bpmnElementType = BpmnElementType.SERVICE_TASK;

              return new ProcessInstanceRecord()
                  .setElementId(elementId)
                  .setBpmnElementType(bpmnElementType)
                  .setBpmnProcessId(wrapString(bpmnProcessId))
                  .setVersion(version)
                  .setProcessDefinitionKey(processDefinitionKey)
                  .setProcessInstanceKey(processInstanceKey)
                  .setFlowScopeKey(flowScopeKey)
                  .setParentProcessInstanceKey(11)
                  .setParentElementInstanceKey(22);
            },
        "{'bpmnProcessId':'test-process','version':12,'processDefinitionKey':13,'processInstanceKey':1234,'elementId':'activity','flowScopeKey':123,'bpmnElementType':'SERVICE_TASK','parentProcessInstanceKey':11,'parentElementInstanceKey':22}"
      },

      /////////////////////////////////////////////////////////////////////////////////////////////
      ///////////////////////////////// Empty ProcessInstanceRecord //////////////////////////////
      /////////////////////////////////////////////////////////////////////////////////////////////
      {
        "Empty ProcessInstanceRecord",
        (Supplier<UnifiedRecordValue>) ProcessInstanceRecord::new,
        "{'bpmnProcessId':'','version':-1,'processDefinitionKey':-1,'processInstanceKey':-1,'elementId':'','flowScopeKey':-1,'bpmnElementType':'UNSPECIFIED','parentProcessInstanceKey':-1,'parentElementInstanceKey':-1}"
      },
    };
  }

  @Test
  public void shouldConvertJsonSerializableToJson() {
    // given

    // when
    final String json = actualRecordSupplier.get().toJson();

    // then
    JsonUtil.assertEquality(json, expectedJson);
  }

  private static String errorRecordAsJson(final long processInstanceKey, final String stacktrace) {
    final Map<String, Object> params = new HashMap<>();
    params.put("exceptionMessage", "test");
    params.put("processInstanceKey", processInstanceKey);
    params.put("errorEventPosition", 123);
    params.put("stacktrace", stacktrace);

    try {
      return new ObjectMapper().writeValueAsString(params);
    } catch (final JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
