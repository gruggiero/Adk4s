# Real LLM Example

This example demonstrates using `StructuredLLM` with real LLM providers (OpenAI, Anthropic, Azure, OpenRouter, Ollama).

## Configuration

The example uses Typesafe Config for configuration management with two levels:

1. **Reference configuration** (`structured-llm-reference.conf` in `structured-llm` module)
   - Provides default values for all providers
   - Includes completion options with sensible defaults

2. **Application configuration** (`application.conf` in `structured-llm-test-models` module)
   - References and overrides default configuration
   - Allows user overrides for provider selection and API keys

### Quick Start

1. Edit `src/main/resources/application.conf` in `structured-llm-test-models` if you want to customize it
2. Edit your configuration file to set:
   - `llm4s.llm.model` - Your provider and model
   - Provider-specific API key (or use environment variables)
3. Run the example:

```bash
sbt "project structured-llm-test-models" "runMain org.adk4s.structured.example.RealLlmExample"
```

## Supported Providers

| Provider | llm.model format | Required Configuration | Environment Variables |
|----------|-------------------|----------------------|----------------------|
| OpenAI | `openai/gpt-4o` | `llm4s.openai.apiKey` | `OPENAI_API_KEY`, `OPENAI_ORGANIZATION` |
| OpenRouter | `openrouter/model-name` | `llm4s.openai.apiKey` | `OPENAI_API_KEY` |
| Anthropic | `anthropic/claude-3-7-sonnet-latest` | `llm4s.anthropic.apiKey` | `ANTHROPIC_API_KEY` |
| Azure | `azure/gpt-4` | `llm4s.azure.endpoint`, `llm4s.azure.apiKey` | `AZURE_API_BASE`, `AZURE_API_KEY` |
| Ollama | `ollama/llama3` | `llm4s.ollama.baseUrl` | - |

## Configuration Options

### LLM Provider Settings

```hocon
llm4s {
  # MANDATORY: Provider and model selection
  llm.model = "openai/gpt-4o"

  # OpenAI / OpenRouter
  openai {
    baseUrl = "https://api.openai.com/v1"
    apiKey = "${?OPENAI_API_KEY}"
    organization = "${?OPENAI_ORGANIZATION}"
  }

  # Anthropic
  anthropic {
    baseUrl = "https://api.anthropic.com"
    apiKey = "${?ANTHROPIC_API_KEY}"
  }

  # Azure OpenAI
  azure {
    endpoint = "https://your-resource.openai.azure.com/"
    apiKey = "${?AZURE_API_KEY}"
    apiVersion = "V2025_01_01_PREVIEW"
  }

  # Ollama
  ollama {
    baseUrl = "http://localhost:11434"
  }
}
```

### Completion Options

```hocon
llm4s.completion {
  temperature = 0.7        # Controls randomness (0.0-2.0)
  topP = 1.0               # Controls diversity (0.0-1.0)
  maxTokens = 1024           # Optional: maximum tokens to generate
  presencePenalty = 0.0      # Penalizes new tokens
  frequencyPenalty = 0.0     # Penalizes repetition
  reasoning = "none"          # none, low, medium, high
  budgetTokens = 16000        # Optional: Anthropic thinking budget
}
```

### Application Settings

```hocon
app {
  # Smithy schema file path (relative to project root or current directory)
  smithy.schema.path = "structured-llm-test-models/src/main/smithy/resume.smithy"

  # Resume input file path (relative to project root or current directory)
  resume.input.path = "samples/resume/sample.txt"

  # Enable/disable full prompt logging
  log.prompt = true
}
```

### Environment Variables

Environment variables **take precedence** over config file values:

- `LLM_MODEL` - Provider/model selection (e.g., `openai/gpt-4o`)
- `OPENAI_API_KEY` - OpenAI API key
- `OPENAI_ORGANIZATION` - OpenAI organization (optional)
- `ANTHROPIC_API_KEY` - Anthropic API key
- `AZURE_API_BASE` - Azure endpoint URL
- `AZURE_API_KEY` - Azure API key

### Example: OpenAI Configuration

```bash
# Set environment variable
export OPENAI_API_KEY="sk-your-openai-key-here"

# Or set in application.conf (not recommended for production):
# llm4s.openai.apiKey = "sk-your-openai-key-here"

# Run
sbt "project structured-llm-test-models" "runMain org.adk4s.structured.example.RealLlmExample"
```

### Example: Anthropic Configuration

```bash
# Set environment variable
export ANTHROPIC_API_KEY="sk-ant-your-key-here"

# Or set in application.conf:
# llm4s.anthropic.apiKey = "sk-ant-your-key-here"

# Update model in application.conf:
# llm4s.llm.model = "anthropic/claude-3-7-sonnet-latest"

# Run
sbt "project structured-llm-test-models" "runMain org.adk4s.structured.example.RealLlmExample"
```

## Path Resolution

File paths in configuration are resolved in this order:

1. **Relative to project root** - Attempted first
   - Example: `structured-llm-test-models/src/main/smithy/resume.smithy`
   - Resolves to: `/path/to/adk4s/structured-llm-test-models/src/main/smithy/resume.smithy`

2. **Relative to current directory** - Fallback
   - Example: `samples/resume/sample.txt`
   - Resolves to: `/path/to/adk4s/samples/resume/sample.txt`

3. **Absolute paths** - Used as-is
   - Example: `/home/user/custom/resume.smithy`

If a file is not found in either location, the application will show both attempted paths and exit gracefully.

## Debug Logging

To enable debug-level logging (shows configuration file path):

```bash
# Add JVM option when running
sbt "project structured-llm-test-models" "runMain org.adk4s.structured.example.RealLlmExample -J-Dapp.log.debug=true"
```

Or in `build.sbt`:

```scala
run / javaOptions ++= Seq("-Dapp.log.debug=true")
```

## Troubleshooting

### Configuration Validation Errors

**Error: "Missing mandatory configuration: llm4s.llm.model"**
- Ensure `llm4s.llm.model` is set in your `application.conf`
- Example: `llm4s.llm.model = "openai/gpt-4o"`

**Error: "Missing mandatory configuration for OpenAI provider: llm4s.openai.apiKey"**
- Set `llm4s.openai.apiKey` in `application.conf`
- Or set environment variable: `OPENAI_API_KEY`

**Error: "File not found" with both attempted paths**
- Check that the file path in configuration is correct
- Verify the file exists at one of the shown locations
- Try using an absolute path instead of relative

### LLM Connection Errors

**Error: "AuthenticationError"**
- Verify your API key is correct
- Check that the API key hasn't expired
- Ensure you're using the correct provider

**Error: "RateLimitError"**
- Wait for the indicated retry delay
- Consider upgrading your API tier
- Implement request throttling in production

**Error: "NetworkError"**
- Check your internet connection
- Verify the base URL is correct
- Check firewall/proxy settings

### Parse Errors

**Error: "Failed to parse LLM response"**
- The LLM returned invalid JSON
- Check the raw response in the error output
- Try adjusting the Smithy schema or prompt
- Verify the model supports structured output

## Sample Files

- `samples/resume/sample.txt` - Example resume text
- `src/main/smithy/resume.smithy` - Smithy schema for Resume type

## Next Steps

- Experiment with different models and providers
- Modify the Smithy schema to extract different information
- Adjust completion options (temperature, reasoning) for different use cases
- Integrate `StructuredLLM` into your own applications
