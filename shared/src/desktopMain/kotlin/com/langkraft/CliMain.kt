package com.langkraft

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.*
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.langkraft.di.createDesktopModule
import com.langkraft.di.initKoin
import com.langkraft.ui.dashboard.DashboardViewModel
import com.langkraft.ui.content.ContentSelectionViewModel
import com.langkraft.ui.player.PlayerViewModel
import com.langkraft.ui.player.PlayerEvent
import com.langkraft.ui.srs.SrsTrainingViewModel
import com.langkraft.ui.writing.WritingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.system.exitProcess

class TuiApp : KoinComponent {
    private val terminalFactory = DefaultTerminalFactory()
    private val screen: Screen = terminalFactory.createScreen()
    private val gui = MultiWindowTextGUI(screen, DefaultWindowManager(), EmptySpace())
    
    // ViewModels
    private val dashboardViewModel: DashboardViewModel by inject()
    private val contentSelectionViewModel: ContentSelectionViewModel by inject()
    private val playerViewModel: PlayerViewModel by inject()
    private val srsTrainingViewModel: SrsTrainingViewModel by inject()
    private val writingViewModel: WritingViewModel by inject()

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun start() {
        screen.startScreen()

        val mainWindow = BasicWindow("Langkraft Console")
        val mainPanel = Panel()
        mainPanel.layoutManager = LinearLayout(Direction.VERTICAL)

        mainPanel.addComponent(Label("Welcome to Langkraft TUI!"))
        mainPanel.addComponent(EmptySpace(TerminalSize(0, 1)))

        mainPanel.addComponent(Button("1. Dashboard", Runnable {
            showDashboard(mainWindow)
        }))
        mainPanel.addComponent(Button("2. Content List", Runnable {
            showContentList(mainWindow)
        }))
        mainPanel.addComponent(Button("3. SRS Review", Runnable {
             showSrsTraining(mainWindow)
        }))
        mainPanel.addComponent(Button("4. Writing Diary", Runnable {
            showWriting(mainWindow)
       }))
        mainPanel.addComponent(EmptySpace(TerminalSize(0, 1)))
        mainPanel.addComponent(Button("Exit", Runnable {
            mainWindow.close()
        }))

        mainWindow.component = mainPanel
        gui.addWindowAndWait(mainWindow)
        screen.stopScreen()
        exitProcess(0)
    }

    private fun showWriting(parent: Window) {
        val window = BasicWindow("Writing Diary")
        val panel = Panel(LinearLayout(Direction.VERTICAL))
        
        val textBox = TextBox(TerminalSize(40, 5))
        textBox.setTextChangeListener { text, _ -> writingViewModel.onTextChanged(text) }
        
        val correctionLabel = Label("")
        
        panel.addComponent(Label("Write in German:"))
        panel.addComponent(textBox)
        panel.addComponent(Button("Check Correction", Runnable {
            writingViewModel.submitForCorrection()
        }))
        panel.addComponent(correctionLabel)
        panel.addComponent(Button("Close", Runnable { window.close() }))
        
        window.component = panel
        
        val job = scope.launch {
            writingViewModel.state.collect { state ->
                gui.guiThread.invokeLater {
                    correctionLabel.text = state.correction?.correctedText ?: (state.error ?: "")
                }
            }
        }

        gui.addWindowAndWait(window)
        job.cancel()
    }

    private fun showSrsTraining(parent: Window) {
        val window = BasicWindow("SRS Training")
        val panel = Panel(LinearLayout(Direction.VERTICAL))
        
        val wordLabel = Label("Loading...")
        val answerLabel = Label("")
        
        panel.addComponent(wordLabel)
        panel.addComponent(answerLabel)
        
        val buttonPanel = Panel(LinearLayout(Direction.HORIZONTAL))
        buttonPanel.addComponent(Button("Show Answer", Runnable {
            srsTrainingViewModel.showAnswer()
        }))
        buttonPanel.addComponent(Button("Easy", Runnable {
            srsTrainingViewModel.submitResult(com.langkraft.domain.model.ReviewQuality.EASY)
        }))
        buttonPanel.addComponent(Button("Hard", Runnable {
            srsTrainingViewModel.submitResult(com.langkraft.domain.model.ReviewQuality.HARD)
        }))
        buttonPanel.addComponent(Button("Close", Runnable { window.close() }))
        
        panel.addComponent(buttonPanel)
        window.component = panel
        
        val job = scope.launch {
            srsTrainingViewModel.state.collect { state ->
                gui.guiThread.invokeLater {
                    wordLabel.text = "Word: ${state.currentWord?.word ?: "No more words"}"
                    answerLabel.text = if (state.isAnswerVisible) "Translation: ${state.currentWord?.translation ?: ""}" else ""
                }
            }
        }

        gui.addWindowAndWait(window)
        job.cancel()
    }

    private fun showContentList(parent: Window) {
        val window = BasicWindow("Content Library")
        val panel = Panel(LinearLayout(Direction.VERTICAL))
        
        val list = ActionListBox()
        list.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Fill))
        
        panel.addComponent(list)
        panel.addComponent(Button("Back", Runnable { window.close() }))
        
        window.component = panel
        
        val job = scope.launch {
            contentSelectionViewModel.state.collect { state ->
                gui.guiThread.invokeLater {
                    list.clearItems()
                    state.library.forEach { item ->
                        list.addItem("${item.title} (${item.durationSeconds}s)") {
                            showPlayer(item.id)
                        }
                    }
                }
            }
        }

        gui.addWindowAndWait(window)
        job.cancel()
    }

    private fun showPlayer(contentId: String) {
        playerViewModel.onEvent(com.langkraft.ui.player.PlayerEvent.LoadContent(contentId))
        
        val window = BasicWindow("Immersion Player")
        val panel = Panel(LinearLayout(Direction.VERTICAL))
        
        val statusLabel = Label("Loading...")
        val timeLabel = Label("0:00 / 0:00")
        
        panel.addComponent(statusLabel)
        panel.addComponent(timeLabel)
        panel.addComponent(Button("Play/Pause", Runnable {
            playerViewModel.onEvent(PlayerEvent.PlayPause)
        }))
        panel.addComponent(Button("Toggle Loop", Runnable {
            playerViewModel.onEvent(PlayerEvent.ToggleLoop)
        }))
        panel.addComponent(Button("Speed 0.75x", Runnable {
            playerViewModel.onEvent(PlayerEvent.SetPlaybackSpeed(0.75f))
        }))
        panel.addComponent(Button("Speed 1.0x", Runnable {
            playerViewModel.onEvent(PlayerEvent.SetPlaybackSpeed(1.0f))
        }))
        panel.addComponent(Button("Close", Runnable { window.close() }))
        
        window.component = panel
        
        val job = scope.launch {
            playerViewModel.state.collect { state ->
                gui.guiThread.invokeLater {
                    statusLabel.text = "${if (state.isPlaying) "Playing" else "Paused"} (Loop: ${state.isLooping}, Speed: ${state.playbackSpeed}x) - ${state.content?.title ?: ""}"
                    timeLabel.text = "${state.currentTimeMs / 1000}s"
                }
            }
        }

        gui.addWindowAndWait(window)
        job.cancel()
    }

    private fun showDashboard(parent: Window) {
        val window = BasicWindow("Dashboard")
        val panel = Panel(LinearLayout(Direction.VERTICAL))
        
        val statsLabel = Label("Loading stats...\n\n\n\n")
        panel.addComponent(statsLabel)
        
        panel.addComponent(EmptySpace(TerminalSize(0, 1)))
        panel.addComponent(Button("Back", Runnable { window.close() }))
        
        window.component = panel
        
        // Listen to state
        val job = scope.launch {
            dashboardViewModel.state.collect { state ->
                gui.guiThread.invokeLater {
                    statsLabel.text = """
                        Stats:
                        Words Mastered: ${state.wordsMastered}
                        Words Learning: ${state.wordsLearning}
                        To Review Today: ${state.wordsToReviewToday}
                        Words Added (Week): ${state.wordsAddedThisWeek}
                        Sync Error: ${state.syncError ?: "None"}
                    """.trimIndent()
                }
            }
        }

        gui.addWindowAndWait(window)
        job.cancel()
    }
}

fun main() {
    initKoin {
        modules(createDesktopModule(isTui = true))
    }
    TuiApp().start()
}