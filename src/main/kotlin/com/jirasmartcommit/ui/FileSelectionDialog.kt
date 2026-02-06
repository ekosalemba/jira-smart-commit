package com.jirasmartcommit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.FileChange
import com.jirasmartcommit.services.FileStatus
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class FileSelectionDialog(
    project: Project,
    private val files: List<FileChange>
) : DialogWrapper(project, true) {

    private val checkBoxList = CheckBoxList<FileChange>()
    private val selectedCountLabel = JBLabel()

    init {
        title = "Select Files to Commit"
        setOKButtonText("Continue")
        setCancelButtonText("Cancel")
        init()
        initFileList()
        updateSelectedCount()
    }

    private fun initFileList() {
        files.forEach { file ->
            val displayText = "${getStatusLabel(file.status)}  ${file.path}"
            checkBoxList.addItem(file, displayText, file.isStaged)
        }
    }

    private fun getStatusLabel(status: FileStatus): String {
        return when (status) {
            FileStatus.ADDED -> "A"
            FileStatus.MODIFIED -> "M"
            FileStatus.DELETED -> "D"
            FileStatus.RENAMED -> "R"
            FileStatus.COPIED -> "C"
            FileStatus.UNTRACKED -> "?"
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(550, 350)

        // Button panel for Select All / Deselect All
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

        val selectAllButton = JButton("Select All").apply {
            addActionListener {
                for (i in 0 until checkBoxList.itemsCount) {
                    checkBoxList.setItemSelected(checkBoxList.getItemAt(i), true)
                }
                checkBoxList.repaint()
                updateSelectedCount()
            }
        }

        val deselectAllButton = JButton("Deselect All").apply {
            addActionListener {
                for (i in 0 until checkBoxList.itemsCount) {
                    checkBoxList.setItemSelected(checkBoxList.getItemAt(i), false)
                }
                checkBoxList.repaint()
                updateSelectedCount()
            }
        }

        buttonPanel.add(selectAllButton)
        buttonPanel.add(deselectAllButton)
        buttonPanel.border = JBUI.Borders.emptyBottom(8)

        // Checkbox list with scroll
        val scrollPane = JBScrollPane(checkBoxList).apply {
            border = JBUI.Borders.empty()
        }

        // Add listener for selection changes
        checkBoxList.setCheckBoxListListener { _, _ ->
            updateSelectedCount()
        }

        // Footer with selected count
        val footerPanel = JPanel(BorderLayout())
        selectedCountLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        footerPanel.add(selectedCountLabel, BorderLayout.WEST)
        footerPanel.border = JBUI.Borders.emptyTop(8)

        // Legend
        val legendLabel = JBLabel("A=Added  M=Modified  D=Deleted  R=Renamed  ?=Untracked").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = font.deriveFont(font.size2D - 1f)
        }
        footerPanel.add(legendLabel, BorderLayout.EAST)

        panel.add(buttonPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(footerPanel, BorderLayout.SOUTH)

        panel.border = JBUI.Borders.empty(10)

        return panel
    }

    private fun updateSelectedCount() {
        val count = getSelectedFiles().size
        val total = files.size
        selectedCountLabel.text = "Selected: $count of $total files"
    }

    fun getSelectedFiles(): List<FileChange> {
        val selected = mutableListOf<FileChange>()
        for (i in 0 until checkBoxList.itemsCount) {
            val item = checkBoxList.getItemAt(i)
            if (item != null && checkBoxList.isItemSelected(item)) {
                selected.add(item)
            }
        }
        return selected
    }

    fun getDeselectedFiles(): List<FileChange> {
        val deselected = mutableListOf<FileChange>()
        for (i in 0 until checkBoxList.itemsCount) {
            val item = checkBoxList.getItemAt(i)
            if (item != null && !checkBoxList.isItemSelected(item)) {
                deselected.add(item)
            }
        }
        return deselected
    }
}
