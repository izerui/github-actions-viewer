package com.github.ghactions.ui

import com.github.ghactions.poll.ViewState
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent

/**
 * 各非数据状态的画面。每种状态都给出下一步动作，
 * 而不只是报错——用户看到的永远是「怎么办」，不是「出错了」。
 */
object EmptyStatePanel {

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun forState(state: ViewState): JComponent {
        val panel = JBPanelWithEmptyText()
        val text = panel.emptyText

        when (state) {
            ViewState.Loading -> text.text = "正在加载…"

            ViewState.NoGitRemote ->
                text.text = "当前项目没有 GitHub remote"

            ViewState.GhNotInstalled -> {
                text.text = "未检测到 GitHub CLI"
                text.appendLine(
                    "前往安装 GitHub CLI",
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) {
                    // 同样不能在 EDT 上唤起浏览器——见 ActionsTreePanel.openInBrowser
                    ApplicationManager.getApplication().executeOnPooledThread {
                        BrowserUtil.browse("https://cli.github.com")
                    }
                }
            }

            ViewState.GhNotLoggedIn -> {
                text.text = "尚未登录 GitHub CLI"
                text.appendLine("请在终端运行：gh auth login", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
                text.appendLine(
                    "复制命令",
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { CopyPasteManager.getInstance().setContents(StringSelection("gh auth login")) }
            }

            is ViewState.RateLimited ->
                text.text = "API 配额已用尽，将于 ${TIME_FORMAT.format(state.resetAt)} 恢复"

            is ViewState.Error ->
                text.text = "加载失败：${state.message}"

            is ViewState.Loaded ->
                text.text = "没有找到工作流运行记录"
        }

        return panel
    }
}
