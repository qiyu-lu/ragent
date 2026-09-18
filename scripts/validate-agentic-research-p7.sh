#!/usr/bin/env bash
set -euo pipefail
p7_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$p7_repo"
export P3_TESTS=${P7_TESTS:-RequestOperationTest,ModelRoutingExecutorTest,HttpPhaseTraceTest,EmbeddingUsageCaptureTest,BaiLianRerankClientTest,ResearchSourceViewTest,ResearchModelSelectionTest,ResearchEvaluationModelsTest,OneShotResearchRunnerTest,ResearchEventStreamServiceTest,ResearchArtifactGeneratorTest,ResearchRunPostgresIT,ResearchWorkerCoordinatorTest,ResearchWorkerNativeTest,ResearchNativeToolsTest,ResearchResumeTest,ResearchBudgetTest,ResearchRunControllerTest,ResearchEvidenceToolsTest,ResearchEvidenceStoreTest,MultiChannelRetrievalEngineTest,RetrievalScopeResolverTest,VectorSearchChannelTest,KeywordSearchChannelTest,PgVectorRetrieverServiceTest,MilvusVectorRetrieverServiceTest,EsKeywordRetrieverServiceTest,RetrievalEngineTest,StreamChatPipelineTest,IngestionTaskServiceImplTest,TableChunkerTest,WorkbookDiffServiceTest,StreamTaskManagerCancelTraceTest}
python3 -m unittest discover -s eval/agentic-research/tests -v
exec bash scripts/validate-agentic-research-p3.sh
