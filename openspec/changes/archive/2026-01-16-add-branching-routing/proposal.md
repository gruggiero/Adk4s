# Change: Add Branching & Routing

## Why
Graph execution requires conditional execution paths based on runtime data, enabling agents to make decisions and route flow through different processing branches dynamically.

## What Changes
- Add Branch ADT with InvokeBranch and StreamBranch variants
- Add Router for managing multiple branch conditions and determining next node
- Add WIOBranch integration for Workflows4s branching patterns (fork, branch, endIf)
- Add package.scala for convenient imports

## Impact
- Affected specs: branching-routing (new capability)
- Affected code: adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/
- Depends on: Feature 01 (Core Types & Schema), Feature 02 (Streaming Integration), Feature 06 (State Management)
