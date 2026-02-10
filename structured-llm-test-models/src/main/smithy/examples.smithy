$version: "2"

namespace org.adk4s.structured.test

/// Classification result with category and confidence score
structure CategoryClassification {
    @required
    category: String
    @required
    confidence: Double
}

/// Role detection result with role name and confidence
structure RoleDetection {
    @required
    role: String
    @required
    confidence: Double
}

/// Query classification with type and intent
structure QueryClassification {
    @required
    queryType: String
    @required
    intent: String
}

/// Chain routing decision with target chain and reason
structure ChainRoute {
    @required
    chainName: String
    @required
    reason: String
}

/// Plan extraction with list of steps and total duration
structure PlanExtraction {
    @required
    steps: PlanSteps
    @required
    totalDuration: Integer
}

/// Individual plan step with index, description, and duration
structure PlanStep {
    @required
    index: Integer
    @required
    description: String
    @required
    duration: Integer
}

/// List of plan steps
list PlanSteps {
    member: PlanStep
}

/// Step list extraction result
structure StepList {
    @required
    items: StepItems
}

/// Individual step item with optional duration
structure StepItem {
    @required
    index: Integer
    @required
    description: String
    duration: Integer
}

/// List of step items
list StepItems {
    member: StepItem
}

/// List parsing result with indexed items
structure ListParsing {
    @required
    items: ListItems
}

/// Individual list item with index and content
structure ListItem {
    @required
    index: Integer
    @required
    content: String
}

/// List of items
list ListItems {
    member: ListItem
}

/// Complex schema extraction example with nested structures
structure SchemaExtraction {
    @required
    title: String
    @required
    author: String
    @required
    tags: StringList
    metadata: ExtractionMetadata
}

/// Metadata for schema extraction
structure ExtractionMetadata {
    @required
    source: String
    @required
    date: String
    confidence: Double
}

/// List of strings
list StringList {
    member: String
}

/// Specialist delegation decision with rationale
structure SpecialistDelegation {
    @required
    specialist: String
    @required
    rationale: String
}

/// Typed intermediate result for chain composition
structure TypedIntermediate {
    @required
    stage: String
    @required
    result: String
    @required
    nextAction: String
}

/// Graph node completion result
structure GraphCompletion {
    @required
    nodeName: String
    @required
    output: String
    @required
    nextNodes: StringList
}
