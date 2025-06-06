package cc.unitmesh.devti.gui.chat.ui

import cc.unitmesh.devti.llm2.TokenUsageEvent
import cc.unitmesh.devti.llm2.TokenUsageListener
import cc.unitmesh.devti.llms.custom.Usage
import cc.unitmesh.devti.settings.AutoDevSettingsState
import cc.unitmesh.devti.settings.model.LLMModelManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants

/**
 * Panel that displays token usage statistics for the current session
 */
class TokenUsagePanel(private val project: Project) : BorderLayoutPanel() {
    private val modelLabel = JBLabel("", SwingConstants.LEFT)
    private val progressBar = JProgressBar(0, 100)
    private val usageRatioLabel = JBLabel("", SwingConstants.CENTER)
    
    private var currentUsage = Usage()
    private var currentModel: String? = null
    private var maxContextWindowTokens: Long = 0
    
    init {
        setupUI()
        setupTokenUsageListener()
    }
    
    private fun setupUI() {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        
        progressBar.apply {
            isStringPainted = false
            preferredSize = java.awt.Dimension(150, 8)
            minimumSize = java.awt.Dimension(100, 8)
            font = font.deriveFont(Font.PLAIN, 10f)
            isOpaque = false
        }
        
        usageRatioLabel.apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = UIUtil.getContextHelpForeground()
        }
        
        val mainPanel = JPanel(GridBagLayout())
        mainPanel.isOpaque = false
        
        val gbc = GridBagConstraints()
        
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.NONE
        // Create left panel for model info
        val leftPanel = JPanel(BorderLayout())
        leftPanel.isOpaque = false
        modelLabel.font = modelLabel.font.deriveFont(Font.PLAIN, 11f)
        modelLabel.foreground = UIUtil.getContextHelpForeground()
        leftPanel.add(modelLabel, BorderLayout.WEST)
        
        mainPanel.add(leftPanel, gbc)
        
        // Progress bar and ratio in the middle
        gbc.gridx = 1
        gbc.weightx = 0.9
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(0, 8)
        
        val progressPanel = JPanel(BorderLayout())
        progressPanel.isOpaque = false
        progressPanel.add(progressBar, BorderLayout.CENTER)
        
        mainPanel.add(progressPanel, gbc)
        
        // Right panel for token count display (10% width)
        val rightPanel = JPanel()
        rightPanel.isOpaque = false
        
        // Add usage ratio label to the right panel
        usageRatioLabel.horizontalAlignment = SwingConstants.RIGHT
        rightPanel.add(usageRatioLabel)
        
        gbc.gridx = 2
        gbc.weightx = 0.1
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        gbc.insets = JBUI.emptyInsets()
        mainPanel.add(rightPanel, gbc)
        
        // Add panels to main layout
        addToCenter(mainPanel)
        
        // Initially hidden
        isVisible = false
    }
    
    private fun setupTokenUsageListener() {
        val messageBus = ApplicationManager.getApplication().messageBus
        messageBus.connect().subscribe(TokenUsageListener.TOPIC, object : TokenUsageListener {
            override fun onTokenUsage(event: TokenUsageEvent) {
                updateTokenUsage(event)
            }
        })
    }
    
    private fun updateTokenUsage(event: TokenUsageEvent) {
        ApplicationManager.getApplication().invokeLater {
            currentUsage = event.usage
            currentModel = event.model
            
            updateMaxTokens()
            updateProgressBar(event.usage.totalTokens ?: 0)

            if (!event.model.isNullOrBlank()) {
                modelLabel.text = "Model: ${event.model}"
            }
            
            isVisible = true
            revalidate()
            repaint()
        }
    }
    
    private fun updateMaxTokens() {
        try {
            val settings = AutoDevSettingsState.getInstance()
            val modelManager = LLMModelManager(project, settings) {}
            val limits = modelManager.getUsedMaxToken()
            maxContextWindowTokens = limits.maxContextWindowTokens?.toLong() ?: 0
        } catch (e: Exception) {
            maxContextWindowTokens = 4096
        }
    }
    
    private fun updateProgressBar(totalTokens: Long) {
        if (maxContextWindowTokens <= 0) {
            progressBar.isVisible = false
            usageRatioLabel.isVisible = false
            return
        }
        
        val usageRatio = (totalTokens.toDouble() / maxContextWindowTokens * 100).toInt()
        progressBar.value = usageRatio.coerceIn(0, 100)
        
        progressBar.foreground = when {
            usageRatio >= 90 -> JBColor.RED
            usageRatio >= 75 -> JBColor.ORANGE
            usageRatio >= 50 -> JBColor.YELLOW
            usageRatio >= 25 -> JBColor.GREEN
            else -> UIUtil.getPanelBackground().brighter()
        }
        
        usageRatioLabel.text = "${formatTokenCount(totalTokens)}/${formatTokenCount(maxContextWindowTokens)} (${usageRatio}%)"
        progressBar.isVisible = true
        usageRatioLabel.isVisible = true
        progressBar.toolTipText = "Token usage: $usageRatio% of context window"
    }
    
    private fun formatTokenCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    fun reset() {
        ApplicationManager.getApplication().invokeLater {
            currentUsage = Usage()
            currentModel = null
            maxContextWindowTokens = 0
            modelLabel.text = ""
            progressBar.value = 0
            progressBar.isVisible = false
            usageRatioLabel.text = ""
            usageRatioLabel.isVisible = false
            isVisible = false
            revalidate()
            repaint()
        }
    }

    fun getCurrentUsage(): Usage = currentUsage

    fun getCurrentModel(): String? = currentModel
}