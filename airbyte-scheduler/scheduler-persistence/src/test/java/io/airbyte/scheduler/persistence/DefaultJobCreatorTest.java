/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.scheduler.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.ActorDefinitionResourceRequirements;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.JobConfig;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.JobResetConnectionConfig;
import io.airbyte.config.JobSyncConfig;
import io.airbyte.config.JobSyncConfig.NamespaceDefinitionType;
import io.airbyte.config.JobTypeResourceLimit;
import io.airbyte.config.JobTypeResourceLimit.JobType;
import io.airbyte.config.OperatorNormalization;
import io.airbyte.config.OperatorNormalization.Option;
import io.airbyte.config.ResetSourceConfiguration;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSyncOperation;
import io.airbyte.config.StandardSyncOperation.OperatorType;
import io.airbyte.config.StreamDescriptor;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.DestinationSyncMode;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.JsonSchemaType;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DefaultJobCreatorTest {

  private static final String STREAM_NAME = "users";
  private static final String FIELD_NAME = "id";

  private static final String SOURCE_IMAGE_NAME = "daxtarity/sourceimagename";
  private static final String DESTINATION_IMAGE_NAME = "daxtarity/destinationimagename";
  private static final SourceConnection SOURCE_CONNECTION;
  private static final DestinationConnection DESTINATION_CONNECTION;
  private static final StandardSync STANDARD_SYNC;
  private static final StandardSyncOperation STANDARD_SYNC_OPERATION;
  private static final long JOB_ID = 12L;
  private static final StreamDescriptor STREAM_DESCRIPTOR1 = new StreamDescriptor().withName("stream 1").withNamespace("namespace 1");
  private static final StreamDescriptor STREAM_DESCRIPTOR2 = new StreamDescriptor().withName("stream 2").withNamespace("namespace 2");

  private JobPersistence jobPersistence;
  private ConfigRepository configRepository;
  private JobCreator jobCreator;
  private ResourceRequirements workerResourceRequirements;

  static {
    final UUID workspaceId = UUID.randomUUID();
    final UUID sourceId = UUID.randomUUID();
    final UUID sourceDefinitionId = UUID.randomUUID();

    final JsonNode implementationJson = Jsons.jsonNode(ImmutableMap.builder()
        .put("apiKey", "123-abc")
        .put("hostname", "airbyte.io")
        .build());

    SOURCE_CONNECTION = new SourceConnection()
        .withWorkspaceId(workspaceId)
        .withSourceDefinitionId(sourceDefinitionId)
        .withSourceId(sourceId)
        .withConfiguration(implementationJson)
        .withTombstone(false);

    final UUID destinationId = UUID.randomUUID();
    final UUID destinationDefinitionId = UUID.randomUUID();

    DESTINATION_CONNECTION = new DestinationConnection()
        .withWorkspaceId(workspaceId)
        .withDestinationDefinitionId(destinationDefinitionId)
        .withDestinationId(destinationId)
        .withConfiguration(implementationJson)
        .withTombstone(false);

    final ConfiguredAirbyteStream stream = new ConfiguredAirbyteStream()
        .withStream(CatalogHelpers.createAirbyteStream(STREAM_NAME, Field.of(FIELD_NAME, JsonSchemaType.STRING)));
    final ConfiguredAirbyteCatalog catalog = new ConfiguredAirbyteCatalog().withStreams(Collections.singletonList(stream));

    final UUID connectionId = UUID.randomUUID();
    final UUID operationId = UUID.randomUUID();

    STANDARD_SYNC = new StandardSync()
        .withConnectionId(connectionId)
        .withName("presto to hudi")
        .withNamespaceDefinition(NamespaceDefinitionType.SOURCE)
        .withNamespaceFormat(null)
        .withPrefix("presto_to_hudi")
        .withStatus(StandardSync.Status.ACTIVE)
        .withCatalog(catalog)
        .withSourceId(sourceId)
        .withDestinationId(destinationId)
        .withOperationIds(List.of(operationId));

    STANDARD_SYNC_OPERATION = new StandardSyncOperation()
        .withOperationId(operationId)
        .withName("normalize")
        .withTombstone(false)
        .withOperatorType(OperatorType.NORMALIZATION)
        .withOperatorNormalization(new OperatorNormalization().withOption(Option.BASIC));
  }

  @BeforeEach
  void setup() {
    jobPersistence = mock(JobPersistence.class);
    configRepository = mock(ConfigRepository.class);
    workerResourceRequirements = new ResourceRequirements()
        .withCpuLimit("0.2")
        .withCpuRequest("0.2")
        .withMemoryLimit("200Mi")
        .withMemoryRequest("200Mi");
    jobCreator = new DefaultJobCreator(jobPersistence, configRepository, workerResourceRequirements);
  }

  @Test
  void testCreateSyncJob() throws IOException {
    final JobSyncConfig jobSyncConfig = new JobSyncConfig()
        .withNamespaceDefinition(STANDARD_SYNC.getNamespaceDefinition())
        .withNamespaceFormat(STANDARD_SYNC.getNamespaceFormat())
        .withPrefix(STANDARD_SYNC.getPrefix())
        .withSourceConfiguration(SOURCE_CONNECTION.getConfiguration())
        .withSourceDockerImage(SOURCE_IMAGE_NAME)
        .withDestinationConfiguration(DESTINATION_CONNECTION.getConfiguration())
        .withDestinationDockerImage(DESTINATION_IMAGE_NAME)
        .withConfiguredAirbyteCatalog(STANDARD_SYNC.getCatalog())
        .withOperationSequence(List.of(STANDARD_SYNC_OPERATION))
        .withResourceRequirements(workerResourceRequirements)
        .withSourceResourceRequirements(workerResourceRequirements)
        .withDestinationResourceRequirements(workerResourceRequirements);

    final JobConfig jobConfig = new JobConfig()
        .withConfigType(JobConfig.ConfigType.SYNC)
        .withSync(jobSyncConfig);

    final String expectedScope = STANDARD_SYNC.getConnectionId().toString();
    when(jobPersistence.enqueueJob(expectedScope, jobConfig)).thenReturn(Optional.of(JOB_ID));

    final long jobId = jobCreator.createSyncJob(
        SOURCE_CONNECTION,
        DESTINATION_CONNECTION,
        STANDARD_SYNC,
        SOURCE_IMAGE_NAME,
        DESTINATION_IMAGE_NAME,
        List.of(STANDARD_SYNC_OPERATION),
        null,
        null).orElseThrow();
    assertEquals(JOB_ID, jobId);
  }

  @Test
  void testCreateSyncJobEnsureNoQueuing() throws IOException {
    final JobSyncConfig jobSyncConfig = new JobSyncConfig()
        .withNamespaceDefinition(STANDARD_SYNC.getNamespaceDefinition())
        .withNamespaceFormat(STANDARD_SYNC.getNamespaceFormat())
        .withPrefix(STANDARD_SYNC.getPrefix())
        .withSourceConfiguration(SOURCE_CONNECTION.getConfiguration())
        .withSourceDockerImage(SOURCE_IMAGE_NAME)
        .withDestinationConfiguration(DESTINATION_CONNECTION.getConfiguration())
        .withDestinationDockerImage(DESTINATION_IMAGE_NAME)
        .withConfiguredAirbyteCatalog(STANDARD_SYNC.getCatalog())
        .withOperationSequence(List.of(STANDARD_SYNC_OPERATION))
        .withResourceRequirements(workerResourceRequirements);

    final JobConfig jobConfig = new JobConfig()
        .withConfigType(JobConfig.ConfigType.SYNC)
        .withSync(jobSyncConfig);

    final String expectedScope = STANDARD_SYNC.getConnectionId().toString();
    when(jobPersistence.enqueueJob(expectedScope, jobConfig)).thenReturn(Optional.empty());

    assertTrue(jobCreator.createSyncJob(
        SOURCE_CONNECTION,
        DESTINATION_CONNECTION,
        STANDARD_SYNC,
        SOURCE_IMAGE_NAME,
        DESTINATION_IMAGE_NAME,
        List.of(STANDARD_SYNC_OPERATION),
        null,
        null).isEmpty());
  }

  @Test
  void testCreateSyncJobDefaultWorkerResourceReqs() throws IOException {
    jobCreator.createSyncJob(
        SOURCE_CONNECTION,
        DESTINATION_CONNECTION,
        STANDARD_SYNC,
        SOURCE_IMAGE_NAME,
        DESTINATION_IMAGE_NAME,
        List.of(STANDARD_SYNC_OPERATION),
        null,
        null);

    final JobSyncConfig expectedJobSyncConfig = new JobSyncConfig()
        .withNamespaceDefinition(STANDARD_SYNC.getNamespaceDefinition())
        .withNamespaceFormat(STANDARD_SYNC.getNamespaceFormat())
        .withPrefix(STANDARD_SYNC.getPrefix())
        .withSourceConfiguration(SOURCE_CONNECTION.getConfiguration())
        .withSourceDockerImage(SOURCE_IMAGE_NAME)
        .withDestinationConfiguration(DESTINATION_CONNECTION.getConfiguration())
        .withDestinationDockerImage(DESTINATION_IMAGE_NAME)
        .withConfiguredAirbyteCatalog(STANDARD_SYNC.getCatalog())
        .withOperationSequence(List.of(STANDARD_SYNC_OPERATION))
        .withResourceRequirements(workerResourceRequirements)
        .withSourceResourceRequirements(workerResourceRequirements)
        .withDestinationResourceRequirements(workerResourceRequirements);

    final JobConfig expectedJobConfig = new JobConfig()
        .withConfigType(JobConfig.ConfigType.SYNC)
        .withSync(expectedJobSyncConfig);

    final String expectedScope = STANDARD_SYNC.getConnectionId().toString();

    verify(jobPersistence, times(1)).enqueueJob(expectedScope, expectedJobConfig);
  }

  @Test
  void testCreateSyncJobConnectionResourceReqs() throws IOException {
    final ResourceRequirements standardSyncResourceRequirements = new ResourceRequirements()
        .withCpuLimit("0.5")
        .withCpuRequest("0.5")
        .withMemoryLimit("500Mi")
        .withMemoryRequest("500Mi");
    final StandardSync standardSync = Jsons.clone(STANDARD_SYNC).withResourceRequirements(standardSyncResourceRequirements);

    jobCreator.createSyncJob(
        SOURCE_CONNECTION,
        DESTINATION_CONNECTION,
        standardSync,
        SOURCE_IMAGE_NAME,
        DESTINATION_IMAGE_NAME,
        List.of(STANDARD_SYNC_OPERATION),
        null,
        null);

    final JobSyncConfig expectedJobSyncConfig = new JobSyncConfig()
        .withNamespaceDefinition(STANDARD_SYNC.getNamespaceDefinition())
        .withNamespaceFormat(STANDARD_SYNC.getNamespaceFormat())
        .withPrefix(STANDARD_SYNC.getPrefix())
        .withSourceConfiguration(SOURCE_CONNECTION.getConfiguration())
        .withSourceDockerImage(SOURCE_IMAGE_NAME)
        .withDestinationConfiguration(DESTINATION_CONNECTION.getConfiguration())
        .withDestinationDockerImage(DESTINATION_IMAGE_NAME)
        .withConfiguredAirbyteCatalog(STANDARD_SYNC.getCatalog())
        .withOperationSequence(List.of(STANDARD_SYNC_OPERATION))
        .withResourceRequirements(standardSyncResourceRequirements)
        .withSourceResourceRequirements(standardSyncResourceRequirements)
        .withDestinationResourceRequirements(standardSyncResourceRequirements);

    final JobConfig expectedJobConfig = new JobConfig()
        .withConfigType(JobConfig.ConfigType.SYNC)
        .withSync(expectedJobSyncConfig);

    final String expectedScope = STANDARD_SYNC.getConnectionId().toString();

    verify(jobPersistence, times(1)).enqueueJob(expectedScope, expectedJobConfig);
  }

  @Test
  void testCreateSyncJobSourceAndDestinationResourceReqs() throws IOException {
    final ResourceRequirements sourceResourceRequirements = new ResourceRequirements()
        .withCpuLimit("0.7")
        .withCpuRequest("0.7")
        .withMemoryLimit("700Mi")
        .withMemoryRequest("700Mi");
    final ResourceRequirements destResourceRequirements = new ResourceRequirements()
        .withCpuLimit("0.8")
        .withCpuRequest("0.8")
        .withMemoryLimit("800Mi")
        .withMemoryRequest("800Mi");

    jobCreator.createSyncJob(
        SOURCE_CONNECTION,
        DESTINATION_CONNECTION,
        STANDARD_SYNC,
        SOURCE_IMAGE_NAME,
        DESTINATION_IMAGE_NAME,
        List.of(STANDARD_SYNC_OPERATION),
        new ActorDefinitionResourceRequirements().withDefault(sourceResourceRequirements),
        new ActorDefinitionResourceRequirements().withJobSpecific(List.of(
            new JobTypeResourceLimit().withJobType(JobType.SYNC).withResourceRequirements(destResourceRequirements))));

    final JobSyncConfig expectedJobSyncConfig = new JobSyncConfig()
        .withNamespaceDefinition(STANDARD_SYNC.getNamespaceDefinition())
        .withNamespaceFormat(STANDARD_SYNC.getNamespaceFormat())
        .withPrefix(STANDARD_SYNC.getPrefix())
        .withSourceConfiguration(SOURCE_CONNECTION.getConfiguration())
        .withSourceDockerImage(SOURCE_IMAGE_NAME)
        .withDestinationConfiguration(DESTINATION_CONNECTION.getConfiguration())
        .withDestinationDockerImage(DESTINATION_IMAGE_NAME)
        .withConfiguredAirbyteCatalog(STANDARD_SYNC.getCatalog())
        .withOperationSequence(List.of(STANDARD_SYNC_OPERATION))
        .withResourceRequirements(workerResourceRequirements)
        .withSourceResourceRequirements(sourceResourceRequirements)
        .withDestinationResourceRequirements(destResourceRequirements);

    final JobConfig expectedJobConfig = new JobConfig()
        .withConfigType(JobConfig.ConfigType.SYNC)
        .withSync(expectedJobSyncConfig);

    final String expectedScope = STANDARD_SYNC.getConnectionId().toString();

    verify(jobPersistence, times(1)).enqueueJob(expectedScope, expectedJobConfig);
  }

  @Test
  void testCreateResetConnectionJob() throws IOException {
    final ConfiguredAirbyteCatalog expectedCatalog = STANDARD_SYNC.getCatalog();
    expectedCatalog.getStreams()
        .forEach(configuredAirbyteStream -> {
          configuredAirbyteStream.setSyncMode(io.airbyte.protocol.models.SyncMode.FULL_REFRESH);
          configuredAirbyteStream.setDestinationSyncMode(DestinationSyncMode.OVERWRITE);
        });

    final JobResetConnectionConfig JobResetConnectionConfig = new JobResetConnectionConfig()
        .withNamespaceDefinition(STANDARD_SYNC.getNamespaceDefinition())
        .withNamespaceFormat(STANDARD_SYNC.getNamespaceFormat())
        .withPrefix(STANDARD_SYNC.getPrefix())
        .withDestinationConfiguration(DESTINATION_CONNECTION.getConfiguration())
        .withDestinationDockerImage(DESTINATION_IMAGE_NAME)
        .withConfiguredAirbyteCatalog(expectedCatalog)
        .withOperationSequence(List.of(STANDARD_SYNC_OPERATION))
        .withResourceRequirements(workerResourceRequirements)
        .withResetSourceConfiguration(new ResetSourceConfiguration().withStreamsToReset(List.of(STREAM_DESCRIPTOR1, STREAM_DESCRIPTOR2)));

    final JobConfig jobConfig = new JobConfig()
        .withConfigType(ConfigType.RESET_CONNECTION)
        .withResetConnection(JobResetConnectionConfig);

    final String expectedScope = STANDARD_SYNC.getConnectionId().toString();
    when(jobPersistence.enqueueJob(expectedScope, jobConfig)).thenReturn(Optional.of(JOB_ID));

    final long jobId = jobCreator.createResetConnectionJob(
        DESTINATION_CONNECTION,
        STANDARD_SYNC,
        DESTINATION_IMAGE_NAME,
        List.of(STANDARD_SYNC_OPERATION),
        List.of(STREAM_DESCRIPTOR1, STREAM_DESCRIPTOR2)).orElseThrow();
    assertEquals(JOB_ID, jobId);
  }

  @Test
  void testCreateResetConnectionJobEnsureNoQueuing() throws IOException {
    final ConfiguredAirbyteCatalog expectedCatalog = STANDARD_SYNC.getCatalog();
    expectedCatalog.getStreams()
        .forEach(configuredAirbyteStream -> {
          configuredAirbyteStream.setSyncMode(io.airbyte.protocol.models.SyncMode.FULL_REFRESH);
          configuredAirbyteStream.setDestinationSyncMode(DestinationSyncMode.OVERWRITE);
        });

    final JobResetConnectionConfig JobResetConnectionConfig = new JobResetConnectionConfig()
        .withNamespaceDefinition(STANDARD_SYNC.getNamespaceDefinition())
        .withNamespaceFormat(STANDARD_SYNC.getNamespaceFormat())
        .withPrefix(STANDARD_SYNC.getPrefix())
        .withDestinationConfiguration(DESTINATION_CONNECTION.getConfiguration())
        .withDestinationDockerImage(DESTINATION_IMAGE_NAME)
        .withConfiguredAirbyteCatalog(expectedCatalog)
        .withOperationSequence(List.of(STANDARD_SYNC_OPERATION))
        .withResourceRequirements(workerResourceRequirements)
        .withResetSourceConfiguration(new ResetSourceConfiguration().withStreamsToReset(List.of(STREAM_DESCRIPTOR1, STREAM_DESCRIPTOR2)));

    final JobConfig jobConfig = new JobConfig()
        .withConfigType(ConfigType.RESET_CONNECTION)
        .withResetConnection(JobResetConnectionConfig);

    final String expectedScope = STANDARD_SYNC.getConnectionId().toString();
    when(jobPersistence.enqueueJob(expectedScope, jobConfig)).thenReturn(Optional.empty());

    assertTrue(jobCreator.createResetConnectionJob(
        DESTINATION_CONNECTION,
        STANDARD_SYNC,
        DESTINATION_IMAGE_NAME,
        List.of(STANDARD_SYNC_OPERATION),
        List.of(STREAM_DESCRIPTOR1, STREAM_DESCRIPTOR2)).isEmpty());
  }

}
