# JIRA Smart Commit - JetBrains Plugin

[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/30062-jira-smart-commit.svg)](https://plugins.jetbrains.com/plugin/30062-jira-smart-commit)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/30062-jira-smart-commit.svg)](https://plugins.jetbrains.com/plugin/30062-jira-smart-commit)

Generate intelligent commit messages and PR descriptions using AI with JIRA integration for JetBrains IDEs (IntelliJ IDEA, WebStorm, PyCharm, GoLand, etc.).

## Features

- **AI-Powered Commit Messages** - Generate conventional commit messages based on staged changes and JIRA ticket context
- **JIRA Integration** - Automatically fetch ticket details from branch name
- **PR Description Generation** - Create comprehensive PR descriptions from commit history
- **Multiple AI Providers** - Support for OpenAI (GPT-4, GPT-4o) and Anthropic (Claude)

## Installation

### From JetBrains Marketplace (Recommended)

1. Open your JetBrains IDE
2. Go to **Settings** → **Plugins** → **Marketplace**
3. Search for **"JIRA Smart Commit"**
4. Click **Install** and restart the IDE

Or install directly from: [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30062-jira-smart-commit)

### Manual Installation

1. Download the latest release from [Releases](https://github.com/ekosalemba/jira-smart-commit/releases) or build from source
2. Go to **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk**
3. Select the downloaded `.zip` file

## Requirements

- JetBrains IDE version 2023.1 or later
- JIRA account with API access
- API key from OpenAI or Anthropic

## Configuration

### Step 1: Open Plugin Settings

Go to **Settings** → **Tools** → **JIRA Smart Commit**

### Step 2: Configure JIRA Connection

| Field | Description | Example |
|-------|-------------|---------|
| JIRA URL | Your JIRA instance URL | `https://company.atlassian.net` |
| Email | Your JIRA account email | `you@company.com` |
| API Token | JIRA API token | `xxxxxxxx` |

> **Get your JIRA API Token:** [Generate here](https://id.atlassian.com/manage-profile/security/api-tokens)

### Step 3: Configure AI Provider

| Field | Description |
|-------|-------------|
| Provider | Select **OpenAI** or **Anthropic** |
| API Key | Your API key from the selected provider |
| Model | Choose the AI model to use |

> **Get API Keys:**
> - OpenAI: [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
> - Anthropic: [console.anthropic.com/settings/keys](https://console.anthropic.com/settings/keys)

## Usage

### Generate Commit Message

1. Stage your changes in Git
2. Open the action using one of these methods:
   - **Menu:** Git → JIRA Smart Commit → Generate Commit Message
   - **Shortcut:** `Ctrl+Alt+G` (Windows/Linux) or `Cmd+Alt+G` (macOS)
3. The plugin will:
   - Detect JIRA ticket from your branch name
   - Analyze your staged changes
   - Generate a conventional commit message
4. Review and edit the message if needed
5. Click **Commit** to apply

### Generate PR Description

1. Ensure you have commits on your feature branch
2. Open the action using one of these methods:
   - **Menu:** Git → JIRA Smart Commit → Generate PR Description
   - **Shortcut:** `Ctrl+Alt+P` (Windows/Linux) or `Cmd+Alt+P` (macOS)
3. Review the generated PR description
4. Click **Copy to Clipboard** and paste into your PR

### Fetch JIRA Ticket Details

- **Menu:** Git → JIRA Smart Commit → Fetch JIRA Ticket
- Automatically extracts ticket key from branch name
- Displays ticket summary, description, and status

## Branch Naming Convention

The plugin automatically extracts JIRA ticket keys from branch names. Supported formats:

- `feature/BOT-1234-description`
- `BOT-1234-description`
- `bugfix/BOT-1234`
- `hotfix/PROJ-123-urgent-fix`

## Commit Message Format

Generated messages follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
Refs: BOT-1234
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`

## Development

### Prerequisites

- JDK 17+
- IntelliJ IDEA (for plugin development)

### Building

```bash
# Build the plugin
./gradlew build

# Run tests
./gradlew test

# Run IDE with plugin (sandbox)
./gradlew runIde
```

### Project Structure

```
src/main/kotlin/com/jirasmartcommit/
├── actions/              # IDE actions
│   ├── GenerateCommitAction.kt
│   ├── GeneratePRDescriptionAction.kt
│   └── FetchJiraTicketAction.kt
├── services/             # Business logic
│   ├── JiraService.kt
│   ├── AIService.kt
│   ├── OpenAIProvider.kt
│   ├── AnthropicProvider.kt
│   └── GitService.kt
├── settings/             # Plugin settings
│   ├── PluginSettings.kt
│   ├── PluginSettingsComponent.kt
│   └── PluginSettingsConfigurable.kt
├── ui/                   # Dialogs
│   ├── CommitMessageDialog.kt
│   ├── PRDescriptionDialog.kt
│   └── JiraTicketPanel.kt
└── util/                 # Utilities
    └── ConventionalCommit.kt
```

## Troubleshooting

### "JIRA connection failed"
- Verify your JIRA URL is correct (include `https://`)
- Check that your API token is valid and not expired
- Ensure your email matches your JIRA account

### "AI generation failed"
- Verify your API key is valid
- Check that you have sufficient API credits
- Try selecting a different model

### "No JIRA ticket found"
- Ensure your branch name contains a valid ticket key (e.g., `feature/PROJ-123-description`)
- Or manually enter the ticket key when prompted

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests: `./gradlew test`
5. Commit your changes
6. Push to the branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

## Support

- **Issues:** [GitHub Issues](https://github.com/ekosalemba/jira-smart-commit/issues)
- **Plugin Page:** [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30062-jira-smart-commit)

## Acknowledgments

Inspired by the VS Code [Jira Smart Commit](https://marketplace.visualstudio.com/items?itemName=AlejandroEsquivias.jira-smart-commit) extension.
