package org.adk4s.core.tools

import munit.FunSuite

class JsonFixMiddlewareTest extends FunSuite:

  test("repair passes valid JSON through unchanged") {
    val input: String = """{"name": "test", "value": 42}"""
    assertEquals(JsonFixMiddleware.repair(input), input)
  }

  test("repair trims whitespace") {
    val input: String = """  {"name": "test"}  """
    assertEquals(JsonFixMiddleware.repair(input), """{"name": "test"}""")
  }

  test("repair isolates JSON object from surrounding text") {
    val input: String = """some noise {"name": "test"} more noise"""
    assertEquals(JsonFixMiddleware.repair(input), """{"name": "test"}""")
  }

  test("repair removes trailing comma before }") {
    val input: String = """{"name": "test", "value": 42,}"""
    val result: String = JsonFixMiddleware.repair(input)
    assertEquals(result, """{"name": "test", "value": 42}""")
  }

  test("repair removes trailing comma before ]") {
    val input: String = """{"items": [1, 2, 3,]}"""
    val result: String = JsonFixMiddleware.repair(input)
    assertEquals(result, """{"items": [1, 2, 3]}""")
  }

  test("repair replaces single quotes with double quotes") {
    val input: String = """{'name': 'test', 'value': 42}"""
    val result: String = JsonFixMiddleware.repair(input)
    assertEquals(result, """{"name": "test", "value": 42}""")
  }

  test("repair removes LLM artifacts") {
    val input: String = """<think>{"name": "test"}</think>"""
    val result: String = JsonFixMiddleware.repair(input)
    assertEquals(result, """{"name": "test"}""")
  }

  test("repair removes FunctionCall markers") {
    val input: String = """<|FunctionCallBegin|>{"name": "test"}<|FunctionCallEnd|>"""
    val result: String = JsonFixMiddleware.repair(input)
    assertEquals(result, """{"name": "test"}""")
  }

  test("repair adds missing leading brace") {
    val input: String = """"name": "test"}"""
    val result: String = JsonFixMiddleware.repair(input)
    assert(result.startsWith("{"))
    assert(result.endsWith("}"))
  }

  test("repair adds missing trailing brace") {
    val input: String = """{"name": "test""""
    val result: String = JsonFixMiddleware.repair(input)
    assert(result.startsWith("{"))
    assert(result.endsWith("}"))
  }

  test("repair strips markdown code fences") {
    val input: String = "```json\n{\"name\": \"test\"}\n```"
    val result: String = JsonFixMiddleware.repair(input)
    assertEquals(result, """{"name": "test"}""")
  }
