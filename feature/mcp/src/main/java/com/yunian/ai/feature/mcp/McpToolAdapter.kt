package com.yunian.ai.feature.mcp

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.McpManager
import com.yunian.ai.domain.McpTool
import com.yunian.ai.domain.ToolRegistry

class McpToolAdapter(
    private val mcpManager: McpManager,
    private val mcpTool: McpTool
) : AiTool {

    override val name = mcpTool.name
    override val description = mcpTool.description
    override val parametersJsonSchema = mcpTool.inputSchema.toString()

    override fun systemPrompt(): String {
        return "MCP Tool: $name - ${mcpTool.description}. Server: ${mcpTool.serverId}"
    }

    override val requiresConfirmation: Boolean = mcpTool.needsApproval

    override suspend fun execute(argumentsJson: String): String {
        return mcpManager.callTool(mcpTool.serverId, mcpTool.name.removePrefix("mcp_${mcpTool.serverId}_"), argumentsJson)
    }
}

class McpToolRegistrar(
    private val mcpManager: McpManager
) {
    private val registeredTools = mutableSetOf<String>()

    suspend fun syncTools() {
        val availableTools = mcpManager.getAvailableTools()
        val currentToolNames = availableTools.map { it.name }.toSet()

        for (toolName in registeredTools - currentToolNames) {
            ToolRegistry.unregister(toolName)
            registeredTools.remove(toolName)
        }

        for (tool in availableTools) {
            if (tool.name !in registeredTools) {
                val adapter = McpToolAdapter(mcpManager, tool)
                ToolRegistry.register(adapter)
                registeredTools.add(tool.name)
            }
        }
    }

    fun unregisterAll() {
        for (toolName in registeredTools) {
            ToolRegistry.unregister(toolName)
        }
        registeredTools.clear()
    }

    fun getRegisteredToolNames(): List<String> = registeredTools.toList()
}
