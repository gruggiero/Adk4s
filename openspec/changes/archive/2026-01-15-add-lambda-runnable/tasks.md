## 1. Runnable Trait Implementation
- [x] 1.1 Create `Runnable.scala` with four streaming paradigms
- [x] 1.2 Implement `fromInvoke` factory with automatic derivation
- [x] 1.3 Implement `fromStream` factory with automatic derivation
- [x] 1.4 Implement `fromCollect` factory with automatic derivation
- [x] 1.5 Implement `fromTransform` factory with automatic derivation
- [x] 1.6 Implement `full` factory for explicit paradigms
- [x] 1.7 Add Runnable unit tests

## 2. Lambda ADT Implementation
- [x] 2.1 Create `Lambda.scala` with sealed trait hierarchy
- [x] 2.2 Implement `LambdaConfig` case class
- [x] 2.3 Implement `InvokableLambda` variant
- [x] 2.4 Implement `StreamableLambda` variant
- [x] 2.5 Implement `CollectableLambda` variant
- [x] 2.6 Implement `TransformableLambda` variant
- [x] 2.7 Implement `FullLambda` variant
- [x] 2.8 Add Lambda factory methods (apply, pure, stream, collect, transform, full)
- [x] 2.9 Add implicit conversion from function to Lambda
- [x] 2.10 Add Lambda unit tests

## 3. Runnable Combinators Implementation
- [x] 3.1 Create `RunnableOps.scala` extension methods
- [x] 3.2 Implement `andThen` sequential composition
- [x] 3.3 Implement `map` output transformation
- [x] 3.4 Implement `evalMap` effectful output transformation
- [x] 3.5 Implement `contramap` input transformation
- [x] 3.6 Implement `timeout` combinator
- [x] 3.7 Implement `handleError` combinator
- [x] 3.8 Implement `parallel` for 2 outputs
- [x] 3.9 Implement `parallel3` for 3 outputs
- [x] 3.10 Add RunnableOps unit tests

## 4. Component Runnable Integration
- [x] 4.1 Create `ComponentRunnable.scala` with ToRunnable typeclass
- [x] 4.2 Implement ToRunnable for ChatModel
- [x] 4.3 Implement ToRunnable for InvokableTool
- [x] 4.4 Implement ToRunnable for StreamableTool
- [x] 4.5 Implement ToRunnable for Lambda
- [x] 4.6 Add extension method `.asRunnable`
- [x] 4.7 Add ComponentRunnable unit tests

## 5. Package and Exports
- [x] 5.1 Create `package.scala` exporting all public APIs
- [x] 5.2 Ensure all types and combinators are accessible

## 6. Documentation
- [ ] 6.1 Update README with Runnable examples
- [ ] 6.2 Add code examples for Lambda creation
- [ ] 6.3 Add code examples for Runnable composition
