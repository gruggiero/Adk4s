package org.adk4s.core

import cats.effect.IO

package object component:
  type ChatModelIO = org.adk4s.core.component.ChatModel[cats.effect.IO]
  type ToolIO = org.adk4s.core.component.Tool[cats.effect.IO]
  type InvokableToolIO = org.adk4s.core.component.InvokableTool[cats.effect.IO]
  type StreamableToolIO = org.adk4s.core.component.StreamableTool[cats.effect.IO]
  type RetrieverIO = org.adk4s.core.component.Retriever[cats.effect.IO]
  type EmbedderIO = org.adk4s.core.component.Embedder[cats.effect.IO]
  type ToolCallingChatModelIO = org.adk4s.core.component.ToolCallingChatModel[cats.effect.IO]

  type AnyToolFunction = org.llm4s.toolapi.ToolFunction[Any, Any]
