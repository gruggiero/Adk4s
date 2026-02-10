## 1. Create Branch Types
- [x] 1.1 Create Branch.scala file with sealed trait Branch[I]
- [x] 1.2 Implement InvokeBranch case class with condition: I => IO[NodeKey]
- [x] 1.3 Implement StreamBranch case class with condition: Stream[IO, I] => IO[NodeKey]
- [x] 1.4 Add endNodes method to Branch trait
- [x] 1.5 Implement Branch companion object with factory methods (apply, pure, stream, binary, endIf)

## 2. Create Router
- [x] 2.1 Create Router.scala file with Router[I] case class
- [x] 2.2 Implement route method for invoke-based routing
- [x] 2.3 Implement routeStream method for stream-based routing with fallback
- [x] 2.4 Implement addBranch method for adding branches
- [x] 2.5 Add Router companion object with empty factory

## 3. Create Workflows4s Integration
- [x] 3.1 Create WIOBranch.scala file
- [x] 3.2 Implement fork method for binary branching in WIO
- [x] 3.3 Implement branch method for multi-way branching in WIO
- [x] 3.4 Implement endIf method for conditional termination

## 4. Create Package Object
- [x] 4.1 Create package.scala with convenient imports for branch API

## 5. Testing
- [x] 5.1 Add unit tests for InvokeBranch routing
- [x] 5.2 Add unit tests for StreamBranch routing
- [x] 5.3 Add unit tests for binary branching
- [x] 5.4 Add unit tests for WIO fork integration
- [x] 5.5 Add unit tests for multi-way branching
- [x] 5.6 Add unit tests for endIf pattern
