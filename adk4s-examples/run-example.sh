#!/bin/bash

# ADK4S Examples Runner Script
# This script provides convenient shortcuts for running ADK4S examples

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if sbt is available
if ! command -v sbt &> /dev/null; then
    print_error "sbt is not installed or not in PATH"
    exit 1
fi

# Function to display usage
usage() {
    echo "ADK4S Examples Runner"
    echo ""
    echo "Usage: $0 [EXAMPLE|all] [OPTIONS]"
    echo ""
    echo "Original Examples:"
    echo "  chain              Run the chain example (graph-based workflow)"
    echo "  simple             Run the simplified chain example"
    echo "  workflow           Run the workflow compilation example"
    echo ""
    echo "Eino Component Examples:"
    echo "  lambda             Runnable modes: invoke, stream, transform, collect"
    echo "  chatmodel          ChatModel generate/stream, multi-turn conversations"
    echo "  chattemplate       Variable substitution, template rendering"
    echo "  docloader          Document loading and chunking strategies"
    echo "  retriever          Keyword-based retrieval pipeline"
    echo "  toolschema         Tool schema inference + JSON fix middleware"
    echo ""
    echo "Eino Graph Examples:"
    echo "  simplegraph        WIOGraph with ChatTemplate + ChatModel"
    echo "  toolcallagent      Tool execution within WIOGraph"
    echo "  twomodelchat       Writer/critic loop using WIOLoopNode"
    echo "  stategraph         Invoke/stream/transform modes via WIORunnableNode"
    echo "  toolcallonce       Conditional tool call branching via WIOForkNode"
    echo "  asyncnode          Async invoke + streaming nodes with delays"
    echo "  reactinterrupt     Human-in-the-loop with InterruptibleNode"
    echo ""
    echo "Eino Workflow Examples:"
    echo "  simpleworkflow     Lambda chain via WIOGraph"
    echo "  branchworkflow     Branching via WIOForkNode"
    echo "  staticvalues       Static value injection via WIOPureNode"
    echo "  fieldmapping       Field mapping via typed lambdas (word counting)"
    echo "  dataonly           Data-only dependencies with explicit field extraction"
    echo "  streamfieldmap     Stream-level field splitting and merging"
    echo ""
    echo "Eino Agent Examples:"
    echo "  reactmemory        Multi-turn conversation with memory"
    echo "  multiagenthost     Host/router with specialist agents"
    echo "  planexecute        Plan-and-execute pattern"
    echo "  reactagent         ReAct agent with tool calling loop + streaming"
    echo "  dynamicoption      Dynamic tool registry with runtime add/remove"
    echo ""
    echo "Agent Orchestration Examples (NEW - Gap Resolution):"
    echo "  agenttool          Basic agent-as-tool delegation"
    echo "  nestedagent        Multi-level nested agent delegation (3 levels)"
    echo "  compositeinterrupt Multiple simultaneous tool interrupts"
    echo "  agenttooladvanced  fromFunction, fromReactAgent, custom schemas"
    echo "  hierarchicalevents Event streaming with RunPath hierarchy"
    echo "  statefulresume     Stateful interrupt/resume with state persistence"
    echo "  interruptresume    Basic interrupt/resume with approval"
    echo "  eventstream        Basic event streaming"
    echo ""
    echo "Eino Batch Examples:"
    echo "  batch              BatchExecutor: sequential, parallel, streaming"
    echo ""
    echo "Eino Quickstart:"
    echo "  chat               Basic multi-turn chat example"
    echo ""
    echo "Structured LLM Examples:"
    echo "  categoryclassification   Category classification (math/science/history)"
    echo "  roledetection            Role detection (customer/support/manager)"
    echo "  queryclassification      Query type classification (question/command)"
    echo "  chainroute               Chain routing decisions"
    echo "  planextraction           Plan extraction with steps and durations"
    echo "  stepsextraction          Steps extraction from instructions"
    echo "  listparsing              List parsing (numbered/bulleted)"
    echo "  schemaextraction         Complex nested schema extraction"
    echo "  specialistdelegation     Multi-agent specialist delegation"
    echo "  multiagenthostex         Multi-agent host coordination"
    echo "  typedintermediates       Chain with typed intermediates"
    echo "  chaincomposition         Composing multiple parsers"
    echo "  transformchain           Staged transformation pipeline"
    echo "  graphintegration         StructuredLLM in WIOGraph nodes"
    echo "  asyncnodestructured      Async streaming with StructuredLLM"
    echo "  saperrorrecovery         SAP error recovery demonstration"
    echo ""
    echo "Structured ToolCall Examples:"
    echo "  reactagentstructured     ReAct agent with typed tools"
    echo "  dynamictoolregistry      Dynamic tool registry"
    echo "  wiographtool             StructuredToolCall in WIOGraph nodes"
    echo ""
    echo "Special:"
    echo "  all                Run all examples sequentially"
    echo ""
    echo "Options:"
    echo "  --help, -h     Show this help message"
    echo "  --compile      Only compile, don't run"
    echo "  --mock         Force use of mock model (ignore API keys)"
    echo ""
    echo "Environment Variables:"
    echo "  OPENAI_API_KEY     OpenAI API key"
    echo "  LLM_MODEL          Model name (default: gpt-4o-mini)"
    echo "  OPENAI_BASE_URL    OpenAI API base URL"
    echo ""
    echo "Examples:"
    echo "  $0 chain                    # Run chain example"
    echo "  $0 lambda --mock            # Run lambda example with mock model"
    echo "  $0 chatmodel --compile      # Only compile chatmodel example"
    echo "  $0 all --mock               # Run all examples with mock model"
}

# Parse arguments
EXAMPLE=""
COMPILE_ONLY=false
FORCE_MOCK=false

while [[ $# -gt 0 ]]; do
    case $1 in
        all|chain|simple|workflow|lambda|chatmodel|chattemplate|docloader|retriever|toolschema|simplegraph|toolcallagent|twomodelchat|stategraph|toolcallonce|asyncnode|reactinterrupt|simpleworkflow|branchworkflow|staticvalues|fieldmapping|dataonly|streamfieldmap|reactmemory|multiagenthost|planexecute|reactagent|dynamicoption|batch|chat|categoryclassification|roledetection|queryclassification|chainroute|planextraction|stepsextraction|listparsing|schemaextraction|specialistdelegation|multiagenthostex|typedintermediates|chaincomposition|transformchain|graphintegration|asyncnodestructured|saperrorrecovery|reactagentstructured|dynamictoolregistry|wiographtool|agenttool|nestedagent|compositeinterrupt|agenttooladvanced|hierarchicalevents|statefulresume|interruptresume|eventstream)
            EXAMPLE="$1"
            shift
            ;;
        --compile)
            COMPILE_ONLY=true
            shift
            ;;
        --mock)
            FORCE_MOCK=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# All examples in order
ALL_EXAMPLES=(
    "lambda"
    "chatmodel"
    "chattemplate"
    "docloader"
    "retriever"
    "simplegraph"
    "toolcallagent"
    "twomodelchat"
    "stategraph"
    "toolcallonce"
    "asyncnode"
    "simpleworkflow"
    "branchworkflow"
    "staticvalues"
    "reactmemory"
    "multiagenthost"
    "planexecute"
    "chat"
    "batch"
    "reactagent"
    "dynamicoption"
    "reactinterrupt"
    "fieldmapping"
    "dataonly"
    "streamfieldmap"
    "toolschema"
    "chain"
    "simple"
    "workflow"
    "categoryclassification"
    "roledetection"
    "queryclassification"
    "chainroute"
    "planextraction"
    "stepsextraction"
    "listparsing"
    "schemaextraction"
    "specialistdelegation"
    "multiagenthostex"
    "typedintermediates"
    "chaincomposition"
    "transformchain"
    "graphintegration"
    "asyncnodestructured"
    "saperrorrecovery"
    "reactagentstructured"
    "dynamictoolregistry"
    "wiographtool"
    "agenttool"
    "nestedagent"
    "compositeinterrupt"
    "agenttooladvanced"
    "hierarchicalevents"
    "statefulresume"
    "interruptresume"
    "eventstream"
)

# Function to resolve example name to main class
resolve_main_class() {
    case $1 in
    chain)
        echo "org.adk4s.examples.chain.ChainExample"
        ;;
    simple)
        echo "org.adk4s.examples.chain.SimpleChainExample"
        ;;
    workflow)
        echo "org.adk4s.examples.chain.WorkflowExample"
        ;;
    lambda)
        echo "org.adk4s.examples.eino.components.LambdaExample"
        ;;
    chatmodel)
        echo "org.adk4s.examples.eino.components.ChatModelExample"
        ;;
    chattemplate)
        echo "org.adk4s.examples.eino.components.ChatTemplateExample"
        ;;
    docloader)
        echo "org.adk4s.examples.eino.components.DocumentLoaderExample"
        ;;
    retriever)
        echo "org.adk4s.examples.eino.components.RetrieverExample"
        ;;
    simplegraph)
        echo "org.adk4s.examples.eino.graph.SimpleGraphExample"
        ;;
    toolcallagent)
        echo "org.adk4s.examples.eino.graph.ToolCallAgentExample"
        ;;
    twomodelchat)
        echo "org.adk4s.examples.eino.graph.TwoModelChatExample"
        ;;
    stategraph)
        echo "org.adk4s.examples.eino.graph.StateGraphExample"
        ;;
    toolcallonce)
        echo "org.adk4s.examples.eino.graph.ToolCallOnceExample"
        ;;
    asyncnode)
        echo "org.adk4s.examples.eino.graph.AsyncNodeExample"
        ;;
    simpleworkflow)
        echo "org.adk4s.examples.eino.workflow.SimpleWorkflowExample"
        ;;
    branchworkflow)
        echo "org.adk4s.examples.eino.workflow.BranchWorkflowExample"
        ;;
    staticvalues)
        echo "org.adk4s.examples.eino.workflow.StaticValuesExample"
        ;;
    reactmemory)
        echo "org.adk4s.examples.eino.agent.ReactMemoryExample"
        ;;
    multiagenthost)
        echo "org.adk4s.examples.eino.agent.MultiAgentHostExample"
        ;;
    planexecute)
        echo "org.adk4s.examples.eino.agent.PlanExecuteExample"
        ;;
    chat)
        echo "org.adk4s.examples.eino.quickstart.ChatExample"
        ;;
    batch)
        echo "org.adk4s.examples.eino.batch.BatchExample"
        ;;
    reactagent)
        echo "org.adk4s.examples.eino.agent.ReactAgentExample"
        ;;
    dynamicoption)
        echo "org.adk4s.examples.eino.agent.DynamicOptionExample"
        ;;
    reactinterrupt)
        echo "org.adk4s.examples.eino.graph.ReactWithInterruptExample"
        ;;
    fieldmapping)
        echo "org.adk4s.examples.eino.workflow.FieldMappingWorkflowExample"
        ;;
    dataonly)
        echo "org.adk4s.examples.eino.workflow.DataOnlyWorkflowExample"
        ;;
    streamfieldmap)
        echo "org.adk4s.examples.eino.workflow.StreamFieldMapExample"
        ;;
    toolschema)
        echo "org.adk4s.examples.eino.components.ToolSchemaExample"
        ;;
    categoryclassification)
        echo "org.adk4s.examples.structured.llm.classification.CategoryClassificationStructuredExample"
        ;;
    roledetection)
        echo "org.adk4s.examples.structured.llm.classification.RoleDetectionStructuredExample"
        ;;
    queryclassification)
        echo "org.adk4s.examples.structured.llm.classification.QueryClassificationStructuredExample"
        ;;
    chainroute)
        echo "org.adk4s.examples.structured.llm.classification.ChainRouteStructuredExample"
        ;;
    planextraction)
        echo "org.adk4s.examples.structured.llm.extraction.PlanExecuteStructuredExample"
        ;;
    stepsextraction)
        echo "org.adk4s.examples.structured.llm.extraction.StepsExtractionStructuredExample"
        ;;
    listparsing)
        echo "org.adk4s.examples.structured.llm.extraction.ListParsingStructuredExample"
        ;;
    schemaextraction)
        echo "org.adk4s.examples.structured.llm.extraction.SchemaExtractionStructuredExample"
        ;;
    specialistdelegation)
        echo "org.adk4s.examples.structured.llm.multiagent.SpecialistDelegationStructuredExample"
        ;;
    multiagenthostex)
        echo "org.adk4s.examples.structured.llm.multiagent.MultiAgentHostStructuredExample"
        ;;
    typedintermediates)
        echo "org.adk4s.examples.structured.llm.chain.TypedIntermediatesStructuredExample"
        ;;
    chaincomposition)
        echo "org.adk4s.examples.structured.llm.chain.ChainCompositionStructuredExample"
        ;;
    transformchain)
        echo "org.adk4s.examples.structured.llm.chain.TransformChainStructuredExample"
        ;;
    graphintegration)
        echo "org.adk4s.examples.structured.llm.workflow.GraphIntegrationStructuredExample"
        ;;
    asyncnodestructured)
        echo "org.adk4s.examples.structured.llm.workflow.AsyncNodeStructuredExample"
        ;;
    saperrorrecovery)
        echo "org.adk4s.examples.structured.sap.SAPErrorRecoveryStructuredExample"
        ;;
    reactagentstructured)
        echo "org.adk4s.examples.structured.toolcall.ReactAgentStructuredExample"
        ;;
    dynamictoolregistry)
        echo "org.adk4s.examples.structured.toolcall.DynamicToolRegistryStructuredExample"
        ;;
    wiographtool)
        echo "org.adk4s.examples.structured.toolcall.WIOGraphToolStructuredExample"
        ;;
    agenttool)
        echo "org.adk4s.examples.eino.agent.AgentToolExample"
        ;;
    nestedagent)
        echo "org.adk4s.examples.eino.agent.NestedAgentDelegationExample"
        ;;
    compositeinterrupt)
        echo "org.adk4s.examples.eino.agent.CompositeInterruptExample"
        ;;
    agenttooladvanced)
        echo "org.adk4s.examples.eino.agent.AgentToolAdvancedExample"
        ;;
    hierarchicalevents)
        echo "org.adk4s.examples.eino.agent.HierarchicalEventStreamExample"
        ;;
    statefulresume)
        echo "org.adk4s.examples.eino.agent.StatefulResumeExample"
        ;;
    interruptresume)
        echo "org.adk4s.examples.eino.agent.InterruptResumeExample"
        ;;
    eventstream)
        echo "org.adk4s.examples.eino.agent.EventStreamExample"
        ;;
    esac
}

# Function to run a single example
run_single() {
    local example_name="$1"
    local main_class
    main_class=$(resolve_main_class "$example_name")
    print_info "Running ADK4S Example: $example_name"
    print_info "Main class: $main_class"
    sbt "adk4s-examples/runMain $main_class"
    print_info "Example '$example_name' completed!"
}

# Function to run all examples sequentially
run_all() {
    local total=${#ALL_EXAMPLES[@]}
    local passed=0
    local failed=0
    local failed_names=()

    print_info "Running all $total examples sequentially..."
    echo ""

    for i in "${!ALL_EXAMPLES[@]}"; do
        local example="${ALL_EXAMPLES[$i]}"
        local num=$((i + 1))
        print_info "[$num/$total] Running: $example"
        if sbt "adk4s-examples/runMain $(resolve_main_class "$example")" ; then
            passed=$((passed + 1))
            print_info "[$num/$total] PASSED: $example"
        else
            failed=$((failed + 1))
            failed_names+=("$example")
            print_error "[$num/$total] FAILED: $example"
        fi
        echo ""
    done

    echo ""
    print_info "========================================"
    print_info "Results: $passed passed, $failed failed out of $total"
    if [ $failed -gt 0 ]; then
        print_error "Failed examples: ${failed_names[*]}"
        exit 1
    fi
    print_info "All examples passed!"
}

# Check if example is specified
if [ -z "$EXAMPLE" ]; then
    print_error "Please specify an example to run"
    usage
    exit 1
fi

# Check environment variables
if [ "$FORCE_MOCK" = false ]; then
    if [ -z "$OPENAI_API_KEY" ]; then
        print_warning "OPENAI_API_KEY not set, will use mock model"
    else
        print_info "Using OpenAI API with model: ${LLM_MODEL:-gpt-4o-mini}"
    fi
else
    print_info "Forcing mock model usage"
    export OPENAI_API_KEY=""
fi

# Compile if needed
if [ "$COMPILE_ONLY" = true ]; then
    print_info "Compiling example..."
    sbt "adk4s-examples/compile"
    print_info "Compilation completed successfully"
    exit 0
fi

# Run
if [ "$EXAMPLE" = "all" ]; then
    run_all
else
    run_single "$EXAMPLE"
fi
