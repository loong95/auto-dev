// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package cc.unitmesh.devti.mcp

import cc.unitmesh.devti.settings.coder.coderSetting
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.jetbrains.ide.RestService
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType

@Service
class MCPUsageCollector(private val scope: CoroutineScope) {
    fun sendUsage(toolKey: String) {
        // todo
    }
}

class MCPService : RestService() {
    private val serviceName = "AutoDevMCP"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "schemaType"
    }

    override fun getServiceName(): String = serviceName

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        val project = getLastFocusedOrOpenedProject() ?: return null
        if (!project.coderSetting.state.enableMcpServer) {
            logger<MCPService>().info("MCP Server is disabled, skipping validation")
            return null
        }

        val path = urlDecoder.path().split(serviceName).last().trimStart('/')
        val tools = McpToolManager.Companion.getAllTools()

        when (path) {
            "list_tools" -> handleListTools(tools, request, context)
            else -> handleToolExecution(path, tools, request, context, project)
        }
        return null
    }

    private fun handleListTools(
        tools: List<AbstractMcpTool<*>>,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ) {
        val toolsList = tools.map { tool ->
            ToolInfo(
                name = tool.name,
                description = tool.description,
                inputSchema = schemaFromDataClass(tool.argKlass)
            )
        }
        sendJson(toolsList, request, context)
    }

    private fun handleToolExecution(
        path: String,
        tools: List<AbstractMcpTool<*>>,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        project: Project
    ) {
        val tool = tools.find { it.name == path } ?: run {
            sendJson(Response(error = "Unknown tool: $path"), request, context)
            return
        }

        service<MCPUsageCollector>().sendUsage(tool.name)
        val args = try {
            parseArgs(request, tool.argKlass)
        } catch (e: Throwable) {
            logger<MCPService>().warn("Failed to parse arguments for tool $path", e)
            sendJson(Response(error = e.message), request, context)
            return
        }
        val result = try {
            toolHandle(tool, project, args)
        } catch (e: Throwable) {
            logger<MCPService>().warn("Failed to execute tool $path", e)
            Response(error = "Failed to execute tool $path, message ${e.message}")
        }
        sendJson(result, request, context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendJson(data: Any, request: FullHttpRequest, context: ChannelHandlerContext) {
        val jsonString = when (data) {
            is List<*> -> json.encodeToString<List<ToolInfo>>(ListSerializer(ToolInfo.serializer()), data as List<ToolInfo>)
            is Response -> json.encodeToString<Response>(Response.serializer(), data)
            else -> throw IllegalArgumentException("Unsupported type for serialization")
        }
        val outputStream = BufferExposingByteArrayOutputStream()
        outputStream.write(jsonString.toByteArray(StandardCharsets.UTF_8))
        send(outputStream, request, context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> parseArgs(request: FullHttpRequest, klass: KClass<T>): T {
        val body = request.content().toString(StandardCharsets.UTF_8)
        if (body.isEmpty()) {
            return NoArgs as T
        }
        return when (klass) {
            NoArgs::class -> NoArgs as T
            else -> {
                json.decodeFromString(serializer(klass.starProjectedType), body) as T
            }
        }
    }

    private fun <Args : Any> toolHandle(tool: McpTool<Args>, project: Project, args: Any): Response {
        @Suppress("UNCHECKED_CAST")
        return tool.handle(project, args as Args)
    }

    override fun isMethodSupported(method: HttpMethod): Boolean =
        method === HttpMethod.GET || method === HttpMethod.POST

    private fun schemaFromDataClass(kClass: KClass<*>): JsonSchemaObject {
        if (kClass == NoArgs::class) return JsonSchemaObject(type = "object")

        val constructor = kClass.primaryConstructor
            ?: error("Class ${kClass.simpleName} must have a primary constructor")

        val properties = constructor.parameters.mapNotNull { param ->
            param.name?.let { name ->
                name to when (param.type.classifier) {
                    String::class -> PropertySchema("string")
                    Int::class, Long::class, Double::class, Float::class -> PropertySchema("number")
                    Boolean::class -> PropertySchema("boolean")
                    List::class -> PropertySchema("array")
                    else -> PropertySchema("object")
                }
            }
        }.toMap()

        val required = constructor.parameters
            .filter { !it.type.isMarkedNullable }
            .mapNotNull { it.name }

        return JsonSchemaObject(
            type = "object",
            properties = properties,
            required = required
        )
    }
}
