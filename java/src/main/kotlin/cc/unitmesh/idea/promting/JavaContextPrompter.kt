package cc.unitmesh.idea.promting

import com.intellij.temporary.similar.chunks.SimilarChunksWithPaths
import cc.unitmesh.devti.gui.chat.ChatActionType
import cc.unitmesh.devti.custom.CustomPromptConfig
import cc.unitmesh.devti.provider.ContextPrompter
import cc.unitmesh.devti.provider.context.ChatCreationContext
import cc.unitmesh.devti.provider.context.ChatOrigin
import cc.unitmesh.devti.settings.AutoDevSettingsState
import cc.unitmesh.idea.MvcUtil
import cc.unitmesh.idea.flow.MvcContextService
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.runBlocking

open class JavaContextPrompter : ContextPrompter() {
    private var additionContext: String = ""
    private val autoDevSettingsState = AutoDevSettingsState.getInstance()
    private var customPromptConfig: CustomPromptConfig? = null
    private lateinit var mvcContextService: MvcContextService
    private var fileName = ""
    private lateinit var creationContext: ChatCreationContext

    override fun appendAdditionContext(context: String) {
        additionContext += context
    }

    override fun initContext(
        actionType: ChatActionType,
        selectedText: String,
        file: PsiFile?,
        project: Project,
        offset: Int,
        element: PsiElement?
    ) {
        super.initContext(actionType, selectedText, file, project, offset, element)
        mvcContextService = MvcContextService(project)

        lang = file?.language?.displayName ?: ""
        fileName = file?.name ?: ""
        creationContext = ChatCreationContext(ChatOrigin.ChatAction, action!!, file, listOf(), element)
    }

    init {
        val prompts = autoDevSettingsState.customEnginePrompts
        customPromptConfig = CustomPromptConfig.tryParse(prompts)
    }

    override fun displayPrompt(): String {
        val instruction = createPrompt(selectedText)

        val finalPrompt = if (additionContext.isNotEmpty()) {
            "```\n$additionContext\n```\n```$lang\n$selectedText\n```\n"
        } else {
            "```$lang\n$selectedText\n```"
        }

        return "$instruction: \n$finalPrompt"
    }

    override fun requestPrompt(): String {
        return runBlocking {
            val instruction = createPrompt(selectedText)
            val chatContext = collectionContext(creationContext)

            var finalPrompt = instruction

            if (chatContext.isNotEmpty()) {
                finalPrompt += "\n$chatContext"
            }

            if (additionContext.isNotEmpty()) {
                finalPrompt += "\n$additionContext"
            }

            finalPrompt += "```$lang\n$selectedText\n```"

            println("final prompt: $finalPrompt")
            logger.info("final prompt: $finalPrompt")
            return@runBlocking finalPrompt
        }
    }


    private fun createPrompt(selectedText: String): String {
        additionContext = ""
        var prompt = action!!.instruction(lang)

        when (action!!) {
            ChatActionType.REVIEW -> {
                val codeReview = customPromptConfig?.codeReview
                if (codeReview?.instruction?.isNotEmpty() == true) {
                    prompt = codeReview.instruction
                }
            }

            ChatActionType.EXPLAIN -> {
                val autoComment = customPromptConfig?.autoComment
                if (autoComment?.instruction?.isNotEmpty() == true) {
                    prompt = autoComment.instruction
                }
            }

            ChatActionType.REFACTOR -> {
                val refactor = customPromptConfig?.refactor
                if (refactor?.instruction?.isNotEmpty() == true) {
                    prompt = refactor.instruction
                }
            }

            ChatActionType.CODE_COMPLETE -> {
                val codeComplete = customPromptConfig?.autoComplete
                if (codeComplete?.instruction?.isNotEmpty() == true) {
                    prompt = codeComplete.instruction
                }

                when {
                    MvcUtil.isController(fileName, lang) -> {
                        val spec = CustomPromptConfig.load().spec["controller"]
                        if (!spec.isNullOrEmpty()) {
                            additionContext = "requirements: \n$spec"
                        }
                        additionContext += mvcContextService.controllerPrompt(file)
                    }

                    MvcUtil.isService(fileName, lang) -> {
                        val spec = CustomPromptConfig.load().spec["service"]
                        if (!spec.isNullOrEmpty()) {
                            additionContext = "requirements: \n$spec"
                        }
                        additionContext += mvcContextService.servicePrompt(file)
                    }

                    else -> {
                        additionContext = SimilarChunksWithPaths.createQuery(file!!) ?: ""
                    }
                }
            }

            ChatActionType.GENERATE_TEST -> {
                val writeTest = customPromptConfig?.writeTest
                if (writeTest?.instruction?.isNotEmpty() == true) {
                    prompt = writeTest.instruction
                }
            }

            ChatActionType.FIX_ISSUE -> {
                addFixIssueContext(selectedText)
            }

            ChatActionType.GEN_COMMIT_MESSAGE -> {
            }

            ChatActionType.CREATE_DDL -> {
                val spec = CustomPromptConfig.load().spec["ddl"]
                if (!spec.isNullOrEmpty()) {
                    additionContext = "requirements: \n$spec"
                }
            }

            ChatActionType.CREATE_CHANGELOG -> {
                prompt = "generate release note base on the follow commit"
            }

            ChatActionType.CHAT -> {
            }

            ChatActionType.CUSTOM_COMPLETE -> {
            }

            ChatActionType.EXPLAIN_BUSINESS -> {
            }

            ChatActionType.CUSTOM_ACTION -> TODO()
        }

        return prompt
    }

    private fun addFixIssueContext(selectedText: String) {
        val projectPath = project!!.basePath ?: ""
        runReadAction {
            val lookupFile = if (selectedText.contains(projectPath)) {
                val regex = Regex("$projectPath(.*\\.)${lang.lowercase()}")
                val relativePath = regex.find(selectedText)?.groupValues?.get(1) ?: ""
                val file = LocalFileSystem.getInstance().findFileByPath(projectPath + relativePath)
                file?.let { PsiManager.getInstance(project!!).findFile(it) }
            } else {
                null
            }

            if (lookupFile != null) {
                additionContext = lookupFile.text.toString()
            }
        }
    }

    companion object {
        val logger = logger<JavaContextPrompter>()
    }
}
