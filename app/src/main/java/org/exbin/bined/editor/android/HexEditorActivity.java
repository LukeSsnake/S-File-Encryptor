/*
 * Copyright (C) ExBin Project, https://exbin.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modified for integration into S-File-Encryptor.
 */

package org.exbin.bined.editor.android;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.Selection;
import android.text.method.KeyListener;
import android.text.method.TextKeyListener;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View.OnUnhandledKeyEventListener;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;

import com.lukestudio.fileencryptor2.R;
import com.rustamg.filedialogs.FileDialog;
import com.rustamg.filedialogs.OpenFileDialog;
import com.rustamg.filedialogs.SaveFileDialog;

import org.exbin.auxiliary.binary_data.delta.DeltaDocument;
import org.exbin.bined.CaretOverlapMode;
import org.exbin.bined.CodeAreaCaretListener;
import org.exbin.bined.CodeAreaCaretPosition;
import org.exbin.bined.DataChangedListener;
import org.exbin.bined.DefaultCodeAreaCaretPosition;
import org.exbin.bined.EditMode;
import org.exbin.bined.EditModeChangedListener;
import org.exbin.bined.EditOperation;
import org.exbin.bined.RowWrappingMode;
import org.exbin.bined.SelectionChangedListener;
import org.exbin.bined.SelectionRange;
import org.exbin.bined.android.CodeAreaAndroidUtils;
import org.exbin.bined.android.Font;
import org.exbin.bined.android.basic.CodeArea;
import org.exbin.bined.android.basic.color.BasicCodeAreaColorsProfile;
import org.exbin.bined.android.capability.ColorAssessorPainterCapable;
import org.exbin.bined.basic.BasicCodeAreaSection;
import org.exbin.bined.basic.CodeAreaViewMode;
import org.exbin.bined.component.BinEdCodeAreaAssessor;
import org.exbin.bined.component.BinaryStatusApi;
import org.exbin.bined.editor.android.inspector.BasicValuesInspector;
import org.exbin.bined.editor.android.inspector.BasicValuesPositionColorModifier;
import org.exbin.bined.editor.android.options.KeysPanelMode;

import org.exbin.bined.editor.android.preference.MainPreferences;
import org.exbin.bined.editor.android.gui.GoToPositionDialog;
import org.exbin.bined.editor.android.gui.EditSelectionDialog;

import org.exbin.bined.editor.android.options.Theme;
import org.exbin.bined.editor.android.preference.BinaryEditorPreferences;
import org.exbin.bined.editor.android.preference.EditorPreferences;
import org.exbin.bined.editor.android.search.BinarySearch;
import org.exbin.bined.editor.android.search.BinarySearchService;
import org.exbin.bined.editor.android.search.BinarySearchServiceImpl;
import org.exbin.bined.editor.android.search.SearchDialog;
import org.exbin.bined.editor.android.search.SearchParameters;
import org.exbin.bined.highlight.android.SearchCodeAreaColorAssessor;
import org.exbin.bined.highlight.android.SearchMatch;
import org.exbin.bined.operation.BinaryDataUndoRedoChangeListener;
import org.exbin.bined.operation.android.CodeAreaOperationCommandHandler;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@NullMarked
public class HexEditorActivity
        extends AppCompatActivity
        implements FileDialog.OnFileSelectedListener {

    private static final int DOUBLE_BACK_KEY_INTERVAL = 3000;

    private static final int SELECTION_START_POPUP_ID = 1;
    private static final int SELECTION_END_POPUP_ID = 2;
    private static final int CLEAR_SELECTION_POPUP_ID = 3;
    private static final int CUT_ACTION_POPUP_ID = 4;
    private static final int COPY_ACTION_POPUP_ID = 5;
    private static final int PASTE_ACTION_POPUP_ID = 6;
    private static final int DELETE_ACTION_POPUP_ID = 7;
    private static final int SELECT_ALL_ACTION_POPUP_ID = 8;
    private static final int COPY_AS_CODE_ACTION_POPUP_ID = 9;
    private static final int PASTE_FROM_CODE_ACTION_POPUP_ID = 10;
    private static final int GO_TO_SIDE_PANEL_POPUP_ID = 11;
    private static final int OPEN_MAIN_MENU_POPUP_ID = 12;
    private static final int SHOW_KEYBOARD_MENU_POPUP_ID = 13;
    private static final int SWITCH_EDIT_MODE_MENU_POPUP_ID = 14;

    private static final int STORAGE_PERMISSION_CODE = 1;

    private String suggestedFileName = null;

    /*
     * IMPORTANT:
     *
     * These objects belong exclusively to this Activity.
     *
     * They must NOT be stored in ApplicationContext.
     */
    private BinEdFileHandler fileHandler;
    private CodeArea codeArea;
    private BasicValuesInspector basicValuesInspector;
    private BinaryEditorPreferences appPreferences;

    private Toolbar toolbar;
    private View keyPanel;
    private View basicValuesInspectorView;

    private @Nullable Menu menu;

    private final BinaryStatusHandler binaryStatus =
            new BinaryStatusHandler(this);

    private BinarySearch binarySearch;

    private View searchStatusPanel;

    private @Nullable Runnable postSaveAsAction = null;

    private boolean keyboardShown = false;
    private boolean dataInspectorShown = true;

    private long lastBackKeyPressTime = -1;
    private long lastReleaseBackKeyPressTime = -1;

    private final BasicValuesPositionColorModifier
            basicValuesPositionColorModifier =
            new BasicValuesPositionColorModifier();

    private FallbackFileType fallbackFileType =
            FallbackFileType.FILE;

    /*
     * Prevents asynchronous search callbacks from updating an Activity
     * that is already being destroyed.
     */
    private boolean editorDestroyed = false;

    private final BinarySearchService.SearchStatusListener
            searchStatusListener =
            new BinarySearchService.SearchStatusListener() {

                @Override
                public void setStatus(
                        BinarySearchService.FoundMatches foundMatches,
                        SearchParameters.MatchMode matchMode) {

                    runOnUiThread(() -> {

                        if (editorDestroyed || isFinishing()) {
                            return;
                        }

                        showSearchStatusPanel();

                        updateSearchStatusPanel(
                                foundMatches.getMatchPosition(),
                                foundMatches.getMatchesCount()
                        );
                    });
                }

                @Override
                public void clearStatus() {

                    runOnUiThread(() -> {

                        if (editorDestroyed || isFinishing()) {
                            return;
                        }

                        hideSearchStatusPanel();
                    });
                }
            };

    private final BinaryDataUndoRedoChangeListener
            codeAreaChangeListener = () -> {

                if (editorDestroyed) {
                    return;
                }

                if (menu != null) {
                    updateUndoState();
                }
            };

    private final DataChangedListener
            codeAreaDataChangedListener = () -> {

                if (editorDestroyed) {
                    return;
                }

                updateCurrentDocumentSize();

                if (binarySearch != null) {
                    binarySearch.cancelSearch();
                    binarySearch.clearSearch();
                }

                hideSearchStatusPanel();
            };

    private final SelectionChangedListener
            codeAreaSelectionChangedListener = () -> {

                if (editorDestroyed) {
                    return;
                }

                binaryStatus.setSelectionRange(
                        codeArea.getSelection()
                );
            };

    private final CodeAreaCaretListener
            codeAreaCodeAreaCaretListener = caretPosition -> {

                if (editorDestroyed) {
                    return;
                }

                binaryStatus.setCursorPosition(caretPosition);

                boolean showKeyboard =
                        codeArea.getActiveSection()
                                == BasicCodeAreaSection.TEXT_PREVIEW;

                if (showKeyboard != keyboardShown) {

                    keyboardShown = showKeyboard;

                    codeArea.requestFocus();

                    codeArea.postDelayed(() -> {

                        if (editorDestroyed) {
                            return;
                        }

                        InputMethodManager im =
                                (InputMethodManager)
                                        getApplication()
                                                .getSystemService(
                                                        Context.INPUT_METHOD_SERVICE
                                                );

                        if (showKeyboard) {

                            im.showSoftInput(
                                    codeArea,
                                    InputMethodManager.SHOW_IMPLICIT
                            );

                        } else {

                            im.hideSoftInputFromWindow(
                                    codeArea.getWindowToken(),
                                    0
                            );
                        }

                    }, 100);
                }
            };

    private final View.OnKeyListener
            codeAreaOnKeyListener =
            new CodeAreaKeyListener();

    /*
     * Strongly typed instead of Object + cast.
     */
    private @Nullable OnUnhandledKeyEventListener
        codeAreaOnUnhandledKeyListener = null;

    private final EditModeChangedListener
            codeAreaEditModeChangedListener =
            binaryStatus::setEditMode;

    private final ActivityResultLauncher<Intent>
            openFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::openFileResultCallback
            );

    private final ActivityResultLauncher<Intent>
            openTableFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::openTableFileResultCallback
            );

    private final ActivityResultLauncher<Intent>
            saveFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::saveFileResultCallback
            );

    private final ActivityResultLauncher<Intent>
            settingsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::settingsResultCallback
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {

            CompatUtils.enableEdgeToEdge(this);

            ViewCompat.setOnApplyWindowInsetsListener(
                    getWindow().getDecorView(),
                    (view, windowInsets) -> {

                        Insets insets =
                                windowInsets.getInsets(
                                        WindowInsetsCompat.Type.navigationBars()
                                );

                        view.setPadding(
                                view.getPaddingLeft(),
                                view.getPaddingTop(),
                                view.getPaddingRight(),
                                insets.bottom
                        );

                        return windowInsets;
                    }
            );
        }

        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );

        getWindow().setStatusBarColor(
                Color.parseColor("#263238")
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
        }

        ApplicationContext application =
                (ApplicationContext) getApplication();

        appPreferences =
                application.getAppPreferences();

        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            getWindow().setStatusBarColor(
                    Color.rgb(38, 50, 56)
            );
        }

        /*
         * Layout diagnostic.
         */
        View main = findViewById(R.id.main);
        View horizontal =
                findViewById(R.id.mainHorizontalLayout);
        View content =
                findViewById(R.id.contentMain);
        View editor =
                findViewById(R.id.codeArea);

        horizontal.post(() ->
                Log.e(
                        "HEX_LAYOUT",
                        "Tela: "
                                + getResources()
                                .getDisplayMetrics()
                                .widthPixels
                                + " | Main: "
                                + main.getWidth()
                                + " | Horizontal: "
                                + horizontal.getWidth()
                                + " | Content: "
                                + content.getWidth()
                                + " | CodeArea: "
                                + editor.getWidth()
                )
        );

        MainPreferences mainPreferences =
                appPreferences.getMainPreferences();

        String theme =
                mainPreferences.getTheme();

        if (Theme.DARK.name().equalsIgnoreCase(theme)) {

            AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_YES
            );

        } else if (Theme.LIGHT.name().equalsIgnoreCase(theme)) {

            AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_NO
            );
        }

        toolbar = findViewById(R.id.toolbar);
        keyPanel = findViewById(R.id.keyPanel);

        LayoutInflater inflater =
                getLayoutInflater();

        searchStatusPanel =
                inflater.inflate(
                        R.layout.search_panel,
                        null
                );

        basicValuesInspectorView =
                findViewById(
                        R.id.basic_values_inspector
                );

        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {

            getSupportActionBar().setTitle(
                    "📝 Hex Editor"
            );
        }

        /*
         * ==========================================================
         * IMPORTANT LIFECYCLE FIX
         * ==========================================================
         *
         * NEVER retrieve an old FileHandler from ApplicationContext.
         *
         * NEVER move an old CodeArea from another Activity into this
         * Activity.
         *
         * The CodeArea from this Activity belongs to this Activity.
         */
        basicValuesInspector =
                new BasicValuesInspector();

        codeArea =
                findViewById(R.id.codeArea);

        fileHandler =
                application.createFileHandler(codeArea);

        if (fileHandler == null) {
            throw new IllegalStateException(
                    "Unable to create BinEd file handler"
            );
        }

        CompatUtils.setApplicationLocales(
                this,
                CompatUtils.getApplicationLocales(this)
        );

        BasicCodeAreaColorsProfile basicColors =
                codeArea.getBasicColors().orElse(null);

        if (basicColors == null) {
            throw new IllegalStateException(
                    "Missing colors profile"
            );
        }

        basicColors.setContext(this);
        basicColors.reinitialize();
        codeArea.resetColors();

        binarySearch =
                new BinarySearch();

        binarySearch.setBinarySearchService(
                new BinarySearchServiceImpl(codeArea)
        );

        registerForContextMenu(codeArea);

        fileHandler.getUndoRedo()
                .addChangeListener(
                        codeAreaChangeListener
                );

        codeArea.addDataChangedListener(
                codeAreaDataChangedListener
        );

        codeArea.addSelectionChangedListener(
                codeAreaSelectionChangedListener
        );

        codeArea.addCaretMovedListener(
                codeAreaCodeAreaCaretListener
        );

        codeArea.addEditModeChangedListener(
                codeAreaEditModeChangedListener
        );

        codeArea.setOnKeyListener(
                codeAreaOnKeyListener
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

            codeAreaOnUnhandledKeyListener =
                    (view, event) ->
                            codeAreaOnKeyListener.onKey(
                                    view,
                                    KeyEvent.KEYCODE_UNKNOWN,
                                    event
                            );

            codeArea.addOnUnhandledKeyEventListener(
                    codeAreaOnUnhandledKeyListener
            );
        }

        basicValuesInspector.setCodeArea(
                codeArea,
                fileHandler.getUndoRedo(),
                basicValuesInspectorView
        );

        basicValuesInspector.enableUpdate();

        BinEdCodeAreaAssessor codeAreaAssessor =
                fileHandler.getCodeAreaAssessor();

        codeAreaAssessor.addColorModifier(
                basicValuesPositionColorModifier
        );

        basicValuesInspector.registerFocusPainter(
                basicValuesPositionColorModifier
        );

        basicValuesInspectorView.setNextFocusUpId(
                R.id.toolbar
        );

        applySettings();

        processIntent(getIntent());

        codeArea.post(
                () -> {

                    if (!editorDestroyed) {
                        codeArea.requestFocus();
                    }
                }
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {

        super.onWindowFocusChanged(hasFocus);

        if (editorDestroyed
                || !hasFocus
                || codeArea == null) {
            return;
        }

        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.Q) {
            return;
        }

        ((CodeAreaOperationCommandHandler)
                codeArea.getCommandHandler())
                .updateCanPaste();
    }

    private void setupKeyPanel(
            KeysPanelMode keysPanelMode
    ) {

        LinearLayout mainView =
                findViewById(R.id.main);

        int keyPanelIndex =
                mainView.indexOfChild(keyPanel);

        if (keysPanelMode == KeysPanelMode.HIDE) {

            if (keyPanelIndex >= 0) {

                mainView.removeViewAt(
                        keyPanelIndex
                );

                mainView.requestLayout();
            }

            return;
        }

        if (keyPanelIndex == -1) {

            mainView.addView(
                    keyPanel,
                    Math.min(
                            2,
                            mainView.getChildCount()
                    )
            );

            mainView.requestLayout();
        }

        switch (keysPanelMode) {

            case SMALL:
                setupKeyPanelSize(60, 40);
                break;

            case MEDIUM:
                setupKeyPanelSize(95, 65);
                break;

            case BIG:
                setupKeyPanelSize(130, 90);
                break;
        }

        keyPanel.requestLayout();
    }

    private void setupKeyPanelSize(
            int buttonWidth,
            int buttonHeight
    ) {

        Button button0 =
                findViewById(R.id.button0);
        button0.setMinWidth(buttonWidth);
        button0.setMinHeight(buttonHeight);

        Button button1 =
                findViewById(R.id.button1);
        button1.setMinWidth(buttonWidth);
        button1.setMinHeight(buttonHeight);

        Button button2 =
                findViewById(R.id.button2);
        button2.setMinWidth(buttonWidth);
        button2.setMinHeight(buttonHeight);

        Button button3 =
                findViewById(R.id.button3);
        button3.setMinWidth(buttonWidth);
        button3.setMinHeight(buttonHeight);

        Button button4 =
                findViewById(R.id.button4);
        button4.setMinWidth(buttonWidth);
        button4.setMinHeight(buttonHeight);

        Button button5 =
                findViewById(R.id.button5);
        button5.setMinWidth(buttonWidth);
        button5.setMinHeight(buttonHeight);

        Button button6 =
                findViewById(R.id.button6);
        button6.setMinWidth(buttonWidth);
        button6.setMinHeight(buttonHeight);

        Button button7 =
                findViewById(R.id.button7);
        button7.setMinWidth(buttonWidth);
        button7.setMinHeight(buttonHeight);

        Button button8 =
                findViewById(R.id.button8);
        button8.setMinWidth(buttonWidth);
        button8.setMinHeight(buttonHeight);

        Button button9 =
                findViewById(R.id.button9);
        button9.setMinWidth(buttonWidth);
        button9.setMinHeight(buttonHeight);

        Button buttonA =
                findViewById(R.id.buttonA);
        buttonA.setMinWidth(buttonWidth);
        buttonA.setMinHeight(buttonHeight);

        Button buttonB =
                findViewById(R.id.buttonB);
        buttonB.setMinWidth(buttonWidth);
        buttonB.setMinHeight(buttonHeight);

        Button buttonC =
                findViewById(R.id.buttonC);
        buttonC.setMinWidth(buttonWidth);
        buttonC.setMinHeight(buttonHeight);

        Button buttonD =
                findViewById(R.id.buttonD);
        buttonD.setMinWidth(buttonWidth);
        buttonD.setMinHeight(buttonHeight);

        Button buttonE =
                findViewById(R.id.buttonE);
        buttonE.setMinWidth(buttonWidth);
        buttonE.setMinHeight(buttonHeight);

        Button buttonF =
                findViewById(R.id.buttonF);
        buttonF.setMinWidth(buttonWidth);
        buttonF.setMinHeight(buttonHeight);

        Button buttonHome =
                findViewById(R.id.buttonHome);
        buttonHome.setMinWidth(buttonWidth);
        buttonHome.setMinHeight(buttonHeight);

        Button buttonEnd =
                findViewById(R.id.buttonEnd);
        buttonEnd.setMinWidth(buttonWidth);
        buttonEnd.setMinHeight(buttonHeight);

        Button buttonLeft =
                findViewById(R.id.buttonLeft);
        buttonLeft.setMinWidth(buttonWidth);
        buttonLeft.setMinHeight(buttonHeight);

        Button buttonRight =
                findViewById(R.id.buttonRight);
        buttonRight.setMinWidth(buttonWidth);
        buttonRight.setMinHeight(buttonHeight);

        Button buttonUp =
                findViewById(R.id.buttonUp);
        buttonUp.setMinWidth(buttonWidth);
        buttonUp.setMinHeight(buttonHeight);

        Button buttonDown =
                findViewById(R.id.buttonDown);
        buttonDown.setMinWidth(buttonWidth);
        buttonDown.setMinHeight(buttonHeight);

        Button buttonDelete =
                findViewById(R.id.buttonDelete);
        buttonDelete.setMinWidth(buttonWidth);
        buttonDelete.setMinHeight(buttonHeight);

        Button buttonBk =
                findViewById(R.id.buttonBk);
        buttonBk.setMinWidth(buttonWidth);
        buttonBk.setMinHeight(buttonHeight);
    }

    private void showSearchStatusPanel() {

        if (editorDestroyed) {
            return;
        }

        LinearLayout mainView =
                findViewById(R.id.main);

        int searchStatusPanelIndex =
                mainView.indexOfChild(
                        searchStatusPanel
                );

        if (searchStatusPanelIndex == -1) {

            TextView searchStatus =
                    searchStatusPanel.findViewById(
                            R.id.searchStatus
                    );

            Button prevButton =
                    searchStatusPanel.findViewById(
                            R.id.previousMatchButton
                    );

            prevButton.setEnabled(false);

            Button nextButton =
                    searchStatusPanel.findViewById(
                            R.id.nextMatchButton
                    );

            nextButton.setEnabled(false);

            Resources resources =
                    getResources();

            searchStatus.setText(
                    resources.getString(
                            R.string.search_in_progress
                    )
            );

            mainView.addView(
                    searchStatusPanel,
                    Math.min(
                            2,
                            mainView.getChildCount()
                    )
            );
        }
    }

    private void hideSearchStatusPanel() {

        if (editorDestroyed) {
            return;
        }

        LinearLayout mainView =
                findViewById(R.id.main);

        int searchStatusPanelIndex =
                mainView.indexOfChild(
                        searchStatusPanel
                );

        if (searchStatusPanelIndex >= 0) {

            mainView.removeViewAt(
                    searchStatusPanelIndex
            );
        }
    }

    private void updateSearchStatusPanel(
            int matchPosition,
            int matchesCount
    ) {

        if (editorDestroyed) {
            return;
        }

        TextView searchStatus =
                searchStatusPanel.findViewById(
                        R.id.searchStatus
                );

        Button prevButton =
                searchStatusPanel.findViewById(
                        R.id.previousMatchButton
                );

        prevButton.setEnabled(
                matchPosition > 0
                        && matchesCount > 0
        );

        Button nextButton =
                searchStatusPanel.findViewById(
                        R.id.nextMatchButton
                );

        nextButton.setEnabled(
                matchPosition < matchesCount - 1
                        && matchesCount > 0
        );

        Resources resources =
                getResources();

        if (matchesCount == 1) {

            searchStatus.setText(
                    resources.getString(
                            R.string.search_match_single
                    )
            );

        } else if (matchesCount > 0) {

            searchStatus.setText(
                    String.format(
                            resources.getString(
                                    R.string.search_match_found
                            ),
                            matchPosition + 1,
                            matchesCount
                    )
            );

        } else {

            searchStatus.setText(
                    resources.getString(
                            R.string.search_match_none
                    )
            );
        }
    }

    /*
     * ==========================================================
     * ACTIVITY CLEANUP
     * ==========================================================
     *
     * The most important part of the lifecycle fix.
     *
     * No ApplicationContext-owned editor is used here.
     */
    @Override
    protected void onDestroy() {

        editorDestroyed = true;

        if (codeArea != null) {
    codeArea.removeSelectionChangedListener(
            menuSelectionChangedListener
    );
}

        /*
         * Stop asynchronous searches first.
         */
        if (binarySearch != null) {

            try {
                binarySearch.cancelSearch();
            } catch (Throwable ignored) {
            }

            try {
                binarySearch.clearSearch();
            } catch (Throwable ignored) {
            }
        }

        /*
        * Remove CodeArea listeners.
        */
        if (codeArea != null) {



            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.P
                    && codeAreaOnUnhandledKeyListener != null) {

                codeArea.removeOnUnhandledKeyEventListener(
                        codeAreaOnUnhandledKeyListener
                );

                codeAreaOnUnhandledKeyListener = null;
            }

            codeArea.setOnKeyListener(null);

            codeArea.removeEditModeChangedListener(
                    codeAreaEditModeChangedListener
            );

            codeArea.removeCaretMovedListener(
                    codeAreaCodeAreaCaretListener
            );

            codeArea.removeSelectionChangedListener(
                    codeAreaSelectionChangedListener
            );

            codeArea.removeDataChangedListener(
                    codeAreaDataChangedListener
            );
        }

        /*
         * Remove Undo/Redo listener.
         */
        if (fileHandler != null) {

            try {
                fileHandler.getUndoRedo()
                        .removeChangeListener(
                                codeAreaChangeListener
                        );
            } catch (Throwable ignored) {
            }

            try {

                BinEdCodeAreaAssessor codeAreaAssessor =
                        fileHandler.getCodeAreaAssessor();

                codeAreaAssessor.removeColorModifier(
                        basicValuesPositionColorModifier
                );

            } catch (Throwable ignored) {
            }
        }

        /*
         * Stop inspector updates.
         */
        if (basicValuesInspector != null) {

            try {
                basicValuesInspector.disableUpdate();
            } catch (Throwable ignored) {
            }
        }

        /*
         * Hide keyboard.
         */
        try {
    if (codeArea != null) {

        InputMethodManager imm =
                (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);

        if (imm != null) {
            imm.hideSoftInputFromWindow(
                    codeArea.getWindowToken(),
                    0
            );
        }
    }
} catch (Throwable ignored) {
}

        /*
         * Clear references owned by this Activity.
         *
         * Do NOT call an invented BinEd dispose()/close() API here.
         * The actual BinEdFileHandler implementation must be inspected
         * before adding such a call.
         */
        
        menu = null;

        super.onDestroy();

    }

    private void processIntent(Intent intent) {

        if (intent == null) {
            return;
        }

        String action =
                intent.getAction();

        if (action == null) {
            return;
        }

        if (Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_EDIT.equals(action)) {

            String scheme =
                    intent.getScheme();

            if (ContentResolver.SCHEME_FILE.equals(scheme)
                    || ContentResolver.SCHEME_CONTENT.equals(scheme)) {

                Uri fileUri =
                        intent.getData();

                if (fileUri != null) {

                    String originalName =
                            getDisplayName(fileUri);

                    releaseFile(() -> {

                        try {

                            fileHandler.openFile(
                                    getContentResolver(),
                                    fileUri,
                                    appPreferences
                                            .getEditorPreferences()
                                            .getFileHandlingMode()
                            );

                            if (originalName != null
                                    && !originalName
                                    .trim()
                                    .isEmpty()) {

                                suggestedFileName =
                                        originalName;
                            }

                            /*
                             * Prevent writing directly back to a
                             * content URI opened from another app.
                             */
                            if (ContentResolver.SCHEME_CONTENT
                                    .equals(scheme)) {

                                fileHandler.clearFileUri();
                            }

                            updateStatus();

                        } catch (Throwable tw) {

                            reportException(tw);
                        }
                    });
                }
            }
        }
    }

    private LocaleListCompat getLanguageLocaleList() {

        String language =
                appPreferences
                        .getMainPreferences()
                        .getLocaleTag();

        if (language.isEmpty()) {
            return LocaleListCompat.getEmptyLocaleList();
        }

        return LocaleListCompat.forLanguageTags(
                language
        );
    }

    private void applySettings() {

        appPreferences
                .getCodeAreaPreferences()
                .applyPreferences(codeArea);

        try {

            codeArea.setCharset(
                    Charset.forName(
                            appPreferences
                                    .getEncodingPreferences()
                                    .getDefaultEncoding()
                    )
            );

        } catch (Exception ex) {

            Logger.getLogger(
                    HexEditorActivity.class.getName()
            ).log(
                    Level.SEVERE,
                    null,
                    ex
            );
        }

        try {

            Font codeFont =
                    new Font();

            int fontSize =
                    appPreferences
                            .getFontPreferences()
                            .getFontSize();

            codeFont.setSize(fontSize);

            codeArea.setCodeFont(
                    codeFont
            );

        } catch (Exception ex) {

            Logger.getLogger(
                    HexEditorActivity.class.getName()
            ).log(
                    Level.SEVERE,
                    null,
                    ex
            );
        }

        binaryStatus.setEncoding(
                codeArea.getCharset().name()
        );

        EditorPreferences editorPreferences =
                appPreferences
                        .getEditorPreferences();

        setupKeyPanel(
                editorPreferences.getKeysPanelMode()
        );

        boolean showDataInspector = false;

        if (showDataInspector != dataInspectorShown) {

            LinearLayout mainHorizontalLayout =
                    findViewById(
                            R.id.mainHorizontalLayout
                    );

            if (showDataInspector) {

                basicValuesInspector.enableUpdate();

                mainHorizontalLayout.addView(
                        basicValuesInspectorView
                );

            } else {

                basicValuesInspector.disableUpdate();

                mainHorizontalLayout.removeView(
                        basicValuesInspectorView
                );
            }

            mainHorizontalLayout.requestLayout();

            dataInspectorShown =
                    showDataInspector;
        }

        boolean isDarkMode =
                (getResources()
                        .getConfiguration()
                        .uiMode
                        & Configuration.UI_MODE_NIGHT_YES)
                        > 0;

        basicValuesPositionColorModifier
                .setDarkMode(isDarkMode);

        /*
         * Automatic row wrapping.
         */
        codeArea.setRowWrapping(
                RowWrappingMode.WRAPPING
        );

        codeArea.setMaxBytesPerRow(16);

        codeArea.requestLayout();

        updateStatus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        getMenuInflater().inflate(
                R.menu.menu_main,
                menu
        );

        if (menu.getClass()
                .getSimpleName()
                .equals("MenuBuilder")) {

            try {

                Method method =
                        menu.getClass()
                                .getDeclaredMethod(
                                        "setOptionalIconsVisible",
                                        Boolean.TYPE
                                );

                method.setAccessible(true);

                method.invoke(
                        menu,
                        true
                );

            } catch (NoSuchMethodException ex) {

                Logger.getLogger(
                        HexEditorActivity.class.getName()
                ).log(
                        Level.SEVERE,
                        null,
                        ex
                );

            } catch (Exception ex) {

                throw new RuntimeException(ex);
            }
        }

        this.menu = menu;

        if (CompatUtils.isAndroidTV(this)) {

            for (int i = 0;
                 i < menu.size();
                 i++) {

                MenuItem menuItem =
                        menu.getItem(i);

                menuItem.setShowAsAction(
                        MenuItem.SHOW_AS_ACTION_NEVER
                );
            }
        }

        codeArea.addSelectionChangedListener(
        menuSelectionChangedListener
);

        updateUndoState();
        updateEditActionsState();

        return true;
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu,
            View v,
            ContextMenu.ContextMenuInfo menuInfo
    ) {

        super.onCreateContextMenu(
                menu,
                v,
                menuInfo
        );

        Resources resources =
                getResources();

        int order = 0;

        if (CompatUtils.isAndroidTV(this)) {

            menu.add(
                    0,
                    GO_TO_SIDE_PANEL_POPUP_ID,
                    order++,
                    resources.getString(
                            R.string.action_go_to_side_panel
                    )
            );

            menu.add(
                    0,
                    OPEN_MAIN_MENU_POPUP_ID,
                    order++,
                    resources.getString(
                            R.string.action_open_main_menu
                    )
            );

            if (codeArea.getCodeAreaCaret()
                    .getSection()
                    == BasicCodeAreaSection.TEXT_PREVIEW) {

                menu.add(
                        0,
                        SHOW_KEYBOARD_MENU_POPUP_ID,
                        order++,
                        resources.getString(
                                R.string.action_show_keyboard
                        )
                );
            }
        }

        menu.add(
                0,
                SELECTION_START_POPUP_ID,
                order,
                resources.getString(
                        R.string.action_selection_start
                )
        );

        menu.add(
                0,
                SELECTION_END_POPUP_ID,
                order + 1,
                resources.getString(
                        R.string.action_selection_end
                )
        );

        menu.add(
                0,
                CLEAR_SELECTION_POPUP_ID,
                order + 2,
                resources.getString(
                        R.string.action_clear_selection
                )
        );

        MenuItem cutMenuItem =
                menu.add(
                        1,
                        CUT_ACTION_POPUP_ID,
                        order + 3,
                        resources.getString(
                                R.string.action_cut
                        )
                );

        cutMenuItem.setEnabled(
                codeArea.isEditable()
                        && codeArea.hasSelection()
        );

        MenuItem copyMenuItem =
                menu.add(
                        1,
                        COPY_ACTION_POPUP_ID,
                        order + 4,
                        resources.getString(
                                R.string.action_copy
                        )
                );

        copyMenuItem.setEnabled(
                codeArea.hasSelection()
        );

        menu.add(
                1,
                COPY_AS_CODE_ACTION_POPUP_ID,
                order + 5,
                resources.getString(
                        R.string.action_copy_as_code
                )
        );

        MenuItem pasteMenuItem =
                menu.add(
                        1,
                        PASTE_ACTION_POPUP_ID,
                        order + 6,
                        resources.getString(
                                R.string.action_paste
                        )
                );

        pasteMenuItem.setEnabled(
                codeArea.isEditable()
                        && codeArea.canPaste()
        );

        menu.add(
                1,
                PASTE_FROM_CODE_ACTION_POPUP_ID,
                order + 7,
                resources.getString(
                        R.string.action_paste_from_code
                )
        );

        MenuItem deleteMenuItem =
                menu.add(
                        1,
                        DELETE_ACTION_POPUP_ID,
                        order + 8,
                        resources.getString(
                                R.string.action_delete
                        )
                );

        deleteMenuItem.setEnabled(
                codeArea.isEditable()
                        && codeArea.hasSelection()
        );

        menu.add(
                1,
                SELECT_ALL_ACTION_POPUP_ID,
                order + 9,
                resources.getString(
                        R.string.action_select_all
                )
        );

        MenuItem switchEditModeMenuItem =
                menu.add(
                        0,
                        SWITCH_EDIT_MODE_MENU_POPUP_ID,
                        order + 10,
                        resources.getString(
                                codeArea.getEditOperation()
                                        == EditOperation.INSERT
                                        ? R.string.action_overwrite_edit_mode
                                        : R.string.action_insert_edit_mode
                        )
                );

        switchEditModeMenuItem.setEnabled(
                codeArea.isEditable()
        );
    }

    @Override
    public boolean onContextItemSelected(
            MenuItem item
    ) {

        switch (item.getItemId()) {

            case GO_TO_SIDE_PANEL_POPUP_ID: {

                LinearLayout mainView =
                        findViewById(R.id.main);

                int searchStatusPanelIndex =
                        mainView.indexOfChild(
                                searchStatusPanel
                        );

                if (searchStatusPanelIndex >= 0) {

                    View closeButton =
                            findViewById(
                                    R.id.closeButton
                            );

                    closeButton.requestFocus();

                    break;
                }

                int keyPanelIndex =
                        mainView.indexOfChild(
                                keyPanel
                        );

                if (keyPanelIndex >= 0) {

                    View downButton =
                            findViewById(
                                    R.id.buttonDown
                            );

                    downButton.requestFocus();

                    break;
                }

                View editTextByte =
                        findViewById(
                                R.id.editTextByte
                        );

                editTextByte.requestFocus();

                break;
            }

            case OPEN_MAIN_MENU_POPUP_ID:

                toolbar.showOverflowMenu();
                break;

            case SHOW_KEYBOARD_MENU_POPUP_ID:

                codeArea.requestFocus();

                codeArea.postDelayed(() -> {

                    if (editorDestroyed) {
                        return;
                    }

                    InputMethodManager im =
                            (InputMethodManager)
                                    getApplication()
                                            .getSystemService(
                                                    Context.INPUT_METHOD_SERVICE
                                            );

                    im.showSoftInput(
                            codeArea,
                            InputMethodManager.SHOW_IMPLICIT
                    );

                }, 100);

                break;

            case SELECTION_START_POPUP_ID:

                fileHandler.selectionByStart(
                        codeArea.mousePositionToClosestCaretPosition(
                                (int) codeArea.getTouchPositionX(),
                                (int) codeArea.getTouchPositionY(),
                                CaretOverlapMode.PARTIAL_OVERLAP
                        )
                );

                break;

            case SELECTION_END_POPUP_ID:

                fileHandler.selectionByEnd(
                        codeArea.mousePositionToClosestCaretPosition(
                                (int) codeArea.getTouchPositionX(),
                                (int) codeArea.getTouchPositionY(),
                                CaretOverlapMode.PARTIAL_OVERLAP
                        )
                );

                break;

            case CLEAR_SELECTION_POPUP_ID:

                fileHandler.clearSelectionPoints();
                break;

            case CUT_ACTION_POPUP_ID:

                try {
                    codeArea.cut();
                } catch (Throwable tw) {
                    reportException(tw);
                }

                break;

            case COPY_ACTION_POPUP_ID:

                try {
                    codeArea.copy();
                } catch (Throwable tw) {
                    reportException(tw);
                }

                break;

            case PASTE_ACTION_POPUP_ID:

                try {
                    codeArea.paste();
                } catch (Throwable tw) {
                    reportException(tw);
                }

                break;

            case DELETE_ACTION_POPUP_ID:

                codeArea.delete();
                break;

            case SELECT_ALL_ACTION_POPUP_ID:

                codeArea.selectAll();
                break;

            case COPY_AS_CODE_ACTION_POPUP_ID:

                try {

                    ((CodeAreaOperationCommandHandler)
                            codeArea.getCommandHandler())
                            .copyAsCode();

                } catch (Throwable tw) {

                    reportException(tw);
                }

                break;

            case PASTE_FROM_CODE_ACTION_POPUP_ID:

                try {

                    ((CodeAreaOperationCommandHandler)
                            codeArea.getCommandHandler())
                            .pasteFromCode();

                } catch (Throwable tw) {

                    reportException(tw);
                }

                break;

            case SWITCH_EDIT_MODE_MENU_POPUP_ID:

                cycleEditMode();
                break;

            default:

                return false;
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(
            MenuItem item
    ) {

        int id =
                item.getItemId();

        if (id == R.id.action_new) {

            releaseFile(() ->
                    fileHandler.setNewData(
                            appPreferences
                                    .getEditorPreferences()
                                    .getFileHandlingMode()
                    )
            );

            return true;

        } else if (id == R.id.action_open) {

            releaseFile(
                    this::openFile
            );

            return true;

        } else if (id == R.id.action_open_table_file) {

            openTableFile();

            return true;

        } else if (id == R.id.action_save) {

            Uri currentFileUri =
                    fileHandler.getCurrentFileUri();

            if (currentFileUri == null) {

                saveAs();

            } else {

                try {

                    fileHandler.saveFile(
                            getContentResolver(),
                            currentFileUri
                    );

                } catch (Throwable tw) {

                    reportException(tw);
                    return false;
                }
            }

            return true;

        } else if (id == R.id.action_save_as) {

            saveAs();

            return true;

        } else if (id == R.id.action_about) {

            showAboutDialog();

            return true;

        } else if (id == R.id.view_mode) {

            AlertDialog.Builder builder =
                    new AlertDialog.Builder(this);

            builder.setTitle(
                    R.string.view_mode
            );

            builder.setSingleChoiceItems(
                    getResources().getTextArray(
                            R.array.view_mode_entries
                    ),
                    codeArea.getViewMode().ordinal(),
                    (dialog, which) -> {

                        CodeAreaViewMode viewMode =
                                CodeAreaViewMode
                                        .values()[which];

                        codeArea.setViewMode(
                                viewMode
                        );

                        appPreferences
                                .getCodeAreaPreferences()
                                .setViewMode(
                                        viewMode
                                );

                        dialog.dismiss();
                    }
            );

            builder.setNegativeButton(
                    R.string.button_cancel,
                    null
            );

            AlertDialog alertDialog =
                    builder.create();

            alertDialog.show();

            return true;

        } else if (id == R.id.action_undo) {

            try {

                fileHandler
                        .getUndoRedo()
                        .performUndo();

            } catch (Throwable tw) {

                reportException(tw);
            }

            codeArea.validateCaret();

            return true;

        } else if (id == R.id.action_redo) {

            try {

                fileHandler
                        .getUndoRedo()
                        .performRedo();

            } catch (Throwable tw) {

                reportException(tw);
            }

            codeArea.validateCaret();

            return true;

        } else if (id == R.id.action_cut) {

            try {
                codeArea.cut();
            } catch (Throwable tw) {
                reportException(tw);
            }

            return true;

        } else if (id == R.id.action_copy) {

            try {
                codeArea.copy();
            } catch (Throwable tw) {
                reportException(tw);
            }

            return true;

        } else if (id == R.id.action_paste) {

            try {
                codeArea.paste();
            } catch (Throwable tw) {
                reportException(tw);
            }

            return true;

        } else if (id == R.id.action_search) {

            SearchDialog searchDialog =
                    new SearchDialog();

            searchDialog.show(
                    getSupportFragmentManager(),
                    "searchDialog"
            );

            return true;

        } else if (id == R.id.action_delete) {

            codeArea.delete();

            return true;

        } else if (id == R.id.action_select_all) {

            codeArea.selectAll();

            return true;

        } else if (id == R.id.go_to_position) {

            goToPosition(codeArea);

            return true;

        } else if (id == R.id.edit_selection) {

            editSelection(codeArea);

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void releaseFile(
            Runnable postReleaseAction
    ) {

        if (!fileHandler.isModified()) {

            postReleaseAction.run();
            return;
        }

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this);

        builder.setTitle(
                R.string.file_modified
        );

        builder.setPositiveButton(
                R.string.button_save,
                (dialog, which) -> {

                    Uri currentFileUri =
                            fileHandler.getCurrentFileUri();

                    if (currentFileUri == null) {

                        /*
                         * Save As is asynchronous.
                         * Do NOT execute postReleaseAction here.
                         * saveFileResultCallback() will continue after
                         * the file has actually been saved.
                         */
                        saveAs(postReleaseAction);

                    } else {

                        try {

                            fileHandler.saveFile(
                                    getContentResolver(),
                                    currentFileUri
                            );

                            /*
                             * Only continue after a successful save.
                             */
                            postReleaseAction.run();

                        } catch (Throwable tw) {

                            /*
                             * IMPORTANT:
                             *
                             * Do not continue after a failed save.
                             */
                            reportException(tw);
                        }
                    }
                }
        );

        builder.setNeutralButton(
                R.string.button_discard,
                (dialog, which) ->
                        postReleaseAction.run()
        );

        builder.setNegativeButton(
                R.string.button_cancel,
                null
        );

        builder.setOnKeyListener(
                (dialog, keyCode, event) -> {

                    if (keyCode == KeyEvent.KEYCODE_BACK
                            && event.getAction()
                            == KeyEvent.ACTION_DOWN) {

                        if (System.currentTimeMillis()
                                - lastReleaseBackKeyPressTime
                                < DOUBLE_BACK_KEY_INTERVAL) {

                            postReleaseAction.run();

                        } else {

                            lastReleaseBackKeyPressTime =
                                    System.currentTimeMillis();

                            Toast.makeText(
                                    HexEditorActivity.this,
                                    getResources().getText(
                                            R.string.confirm_discard
                                    ),
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        return true;
                    }

                    return false;
                }
        );

        AlertDialog alertDialog =
                builder.create();

        alertDialog.show();
    }

    public void openFile() {

        if (CompatUtils.isAndroidTV(this)
                || Build.VERSION.SDK_INT
                < Build.VERSION_CODES.KITKAT) {

            fallBackOpenFile(
                    FallbackFileType.FILE
            );

            return;
        }

        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("*/*");

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {

            intent.putExtra(
                    Intent.EXTRA_LOCALE_LIST,
                    (android.os.Parcelable)
                            getLanguageLocaleList()
                                    .unwrap()
            );
        }

        Uri pickerInitialUri =
                fileHandler.getPickerInitialUri();

        if (pickerInitialUri != null
                && Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            intent.putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    pickerInitialUri
            );
        }

        try {

            openFileLauncher.launch(
                    Intent.createChooser(
                            intent,
                            getResources().getString(
                                    R.string.bined_select_file
                            )
                    )
            );

        } catch (ActivityNotFoundException ex) {

            fallBackOpenFile(
                    FallbackFileType.FILE
            );
        }
    }

    public void openTableFile() {

        if (CompatUtils.isAndroidTV(this)
                || Build.VERSION.SDK_INT
                < Build.VERSION_CODES.KITKAT) {

            fallBackOpenFile(
                    FallbackFileType.TABLE_FILE
            );

            return;
        }

        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("*/*");

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {

            intent.putExtra(
                    Intent.EXTRA_LOCALE_LIST,
                    (android.os.Parcelable)
                            getLanguageLocaleList()
                                    .unwrap()
            );
        }

        try {

            openTableFileLauncher.launch(
                    Intent.createChooser(
                            intent,
                            getResources().getString(
                                    R.string.bined_select_file
                            )
                    )
            );

        } catch (ActivityNotFoundException ex) {

            fallBackOpenFile(
                    FallbackFileType.TABLE_FILE
            );
        }
    }

    private void fallBackOpenFile(
            FallbackFileType fallbackFileType
    ) {

        this.fallbackFileType =
                fallbackFileType;

        boolean permissionGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                        == PackageManager.PERMISSION_GRANTED;

        if (!permissionGranted) {

            requestWriteExternalStoragePermission();
            return;
        }

        OpenFileDialog dialog =
                new OpenFileDialog();

        dialog.setStyle(
                DialogFragment.STYLE_NO_TITLE,
                R.style.BinEdAppTheme
        );

        dialog.show(
                getSupportFragmentManager(),
                OpenFileDialog.class.getName()
        );
    }

    public void saveAs() {
        saveAs(null);
    }

    public void saveAs(
            @Nullable Runnable postSaveAsAction
    ) {

        this.postSaveAsAction =
                postSaveAsAction;

        if (CompatUtils.isAndroidTV(this)
                || Build.VERSION.SDK_INT
                < Build.VERSION_CODES.KITKAT) {

            fallBackSaveAs();
            return;
        }

        Intent intent =
                new Intent(
                        Intent.ACTION_CREATE_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("*/*");

        intent.putExtra(
                Intent.EXTRA_TITLE,
                getCurrentFileName()
        );

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {

            intent.putExtra(
                    Intent.EXTRA_LOCALE_LIST,
                    (android.os.Parcelable)
                            getLanguageLocaleList()
                                    .unwrap()
            );
        }

        Uri pickerInitialUri =
                fileHandler.getPickerInitialUri();

        if (pickerInitialUri != null
                && Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            intent.putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    pickerInitialUri
            );
        }

        try {

            saveFileLauncher.launch(
                    Intent.createChooser(
                            intent,
                            getResources().getString(
                                    R.string.save_as_file
                            )
                    )
            );

        } catch (ActivityNotFoundException ex) {

            fallBackSaveAs();

        } catch (Throwable tw) {

            reportException(tw);
        }
    }

    private void fallBackSaveAs() {

        boolean permissionGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                        == PackageManager.PERMISSION_GRANTED;

        if (permissionGranted) {

            SaveFileDialog dialog =
                    new SaveFileDialog();

            dialog.setStyle(
                    DialogFragment.STYLE_NO_TITLE,
                    R.style.BinEdAppTheme
            );

            dialog.show(
                    getSupportFragmentManager(),
                    SaveFileDialog.class.getName()
            );

        } else {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    STORAGE_PERMISSION_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        boolean permissionGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                        == PackageManager.PERMISSION_GRANTED;

        if (!permissionGranted) {

            Toast.makeText(
                    this,
                    R.string.storage_permission_is_not_granted,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {

        super.onNewIntent(intent);

        /*
         * Keep Activity.getIntent() synchronized with the new intent.
         */
        setIntent(intent);

        processIntent(intent);
    }

    public void updateStatus() {

        if (editorDestroyed) {
            return;
        }

        updateCurrentDocumentSize();
        updateCurrentCaretPosition();
        updateCurrentSelectionRange();
        updateCurrentMemoryMode();
        updateCurrentEditMode();
    }

    private void updateCurrentDocumentSize() {

        if (editorDestroyed) {
            return;
        }

        long dataSize =
                codeArea.getDataSize();

        binaryStatus.setCurrentDocumentSize(
                dataSize,
                fileHandler.getDocumentOriginalSize()
        );
    }

    private void updateCurrentCaretPosition() {

        if (editorDestroyed) {
            return;
        }

        CodeAreaCaretPosition caretPosition =
                codeArea.getActiveCaretPosition();

        binaryStatus.setCursorPosition(
                caretPosition
        );
    }

    private void updateCurrentSelectionRange() {

        if (editorDestroyed) {
            return;
        }

        SelectionRange selectionRange =
                codeArea.getSelection();

        binaryStatus.setSelectionRange(
                selectionRange
        );
    }

    private void updateCurrentMemoryMode() {

        if (editorDestroyed) {
            return;
        }

        BinaryStatusApi.MemoryMode newMemoryMode =
                BinaryStatusApi.MemoryMode.RAM_MEMORY;

        if (codeArea.getEditMode()
                == EditMode.READ_ONLY) {

            newMemoryMode =
                    BinaryStatusApi.MemoryMode.READ_ONLY;

        } else if (codeArea.getContentData()
                instanceof DeltaDocument) {

            newMemoryMode =
                    BinaryStatusApi.MemoryMode.DELTA_MODE;
        }

        binaryStatus.setMemoryMode(
                newMemoryMode
        );
    }

    private void updateCurrentEditMode() {

        if (editorDestroyed) {
            return;
        }

        binaryStatus.setEditMode(
                codeArea.getEditMode(),
                codeArea.getActiveOperation()
        );
    }

    private void updateEditActionsState() {

        if (editorDestroyed || menu == null) {
            return;
        }

        MenuItem cutMenuItem =
                menu.findItem(
                        R.id.action_cut
                );

        cutMenuItem.setEnabled(
                codeArea.isEditable()
                        && codeArea.hasSelection()
        );

        MenuItem copyMenuItem =
                menu.findItem(
                        R.id.action_copy
                );

        copyMenuItem.setEnabled(
                codeArea.hasSelection()
        );

        MenuItem pasteMenuItem =
                menu.findItem(
                        R.id.action_paste
                );

        pasteMenuItem.setEnabled(
                codeArea.isEditable()
                        && codeArea.canPaste()
        );

        MenuItem deleteMenuItem =
                menu.findItem(
                        R.id.action_delete
                );

        deleteMenuItem.setEnabled(
                codeArea.isEditable()
                        && codeArea.hasSelection()
        );
    }

    private void updateUndoState() {

        if (editorDestroyed || menu == null) {
            return;
        }

        MenuItem saveMenuItem =
                menu.findItem(
                        R.id.action_save
                );

        saveMenuItem.setEnabled(
                fileHandler.isModified()
        );

        boolean canUndo =
                fileHandler
                        .getUndoRedo()
                        .canUndo();

        MenuItem undoMenuItem =
                menu.findItem(
                        R.id.action_undo
                );

        undoMenuItem.setEnabled(
                canUndo
        );

        boolean canRedo =
                fileHandler
                        .getUndoRedo()
                        .canRedo();

        MenuItem redoMenuItem =
                menu.findItem(
                        R.id.action_redo
                );

        redoMenuItem.setEnabled(
                canRedo
        );
    }

    private void openFileResultCallback(
            ActivityResult activityResult
    ) {

        int resultCode =
                activityResult.getResultCode();

        Intent data =
                activityResult.getData();

        if (resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {

            return;
        }

        Uri fileUri =
                data.getData();

        /*
         * IMPORTANT:
         *
         * Opening another file is a release operation.
         * Therefore it must respect the modified-file dialog.
         */
        releaseFile(() -> {

            try {

                String originalName =
                        getDisplayName(fileUri);

                fileHandler.openFile(
                        getContentResolver(),
                        fileUri,
                        appPreferences
                                .getEditorPreferences()
                                .getFileHandlingMode()
                );

                if (originalName != null
                        && !originalName
                        .trim()
                        .isEmpty()) {

                    suggestedFileName =
                            originalName;
                }

                /*
                 * Prevent saving back into a content URI owned by
                 * another application.
                 */
                if (ContentResolver.SCHEME_CONTENT.equals(
                        fileUri.getScheme()
                )) {

                    fileHandler.clearFileUri();
                }

                updateStatus();

            } catch (Throwable tw) {

                reportException(tw);
            }
        });
    }

    private void openTableFileResultCallback(
            ActivityResult activityResult
    ) {

        CodeAreaTableMapAssessor
                codeAreaTableMapAssessor =
                fileHandler
                        .getCodeAreaTableMapAssessor();

        int resultCode =
                activityResult.getResultCode();

        Intent data =
                activityResult.getData();

        if (resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {

            codeAreaTableMapAssessor
                    .setUseTable(false);

            return;
        }

        try {

            codeAreaTableMapAssessor.openFile(
                    getContentResolver(),
                    data.getData()
            );

            fileHandler
                    .getCodeArea()
                    .repaint();

        } catch (Throwable tw) {

            reportException(tw);
        }
    }

    private void saveFileResultCallback(
            ActivityResult activityResult
    ) {

        int resultCode =
                activityResult.getResultCode();

        Intent data =
                activityResult.getData();

        if (resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {

            return;
        }

        try {

            fileHandler.saveFile(
                    getContentResolver(),
                    data.getData()
            );

            if (postSaveAsAction != null) {

                Runnable action =
                        postSaveAsAction;

                postSaveAsAction = null;

                action.run();
            }

        } catch (Throwable tw) {

            /*
             * Do NOT execute postSaveAsAction after a failed save.
             */
            reportException(tw);
        }
    }

    @Override
    public void onFileSelected(
            FileDialog dialog,
            File file
    ) {

        if (dialog instanceof OpenFileDialog) {

            switch (fallbackFileType) {

                case FILE:

                    try {

                        fileHandler.openFile(
                                getContentResolver(),
                                Uri.fromFile(file),
                                appPreferences
                                        .getEditorPreferences()
                                        .getFileHandlingMode()
                        );

                        updateStatus();

                    } catch (Throwable tw) {

                        reportException(tw);
                    }

                    break;

                case TABLE_FILE:

                    try {

                        fileHandler
                                .getCodeAreaTableMapAssessor()
                                .openFile(
                                        getContentResolver(),
                                        Uri.fromFile(file)
                                );

                        fileHandler
                                .getCodeArea()
                                .repaint();

                    } catch (Throwable tw) {

                        reportException(tw);
                    }

                    break;
            }

        } else {

            try {

                fileHandler.saveFile(
                        getContentResolver(),
                        Uri.fromFile(file)
                );

                if (postSaveAsAction != null) {

                    Runnable action =
                            postSaveAsAction;

                    postSaveAsAction = null;

                    action.run();
                }

            } catch (Throwable tw) {

                reportException(tw);
            }
        }
    }

    private void settingsResultCallback(
            ActivityResult activityResult
    ) {

        applySettings();

        if (menu != null) {
            // updateViewActionsState();
        }
    }

    public void buttonAction0(View view) {

        codeArea.getCommandHandler().keyTyped(
                '0',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_0
                )
        );
    }

    public void buttonAction1(View view) {

        codeArea.getCommandHandler().keyTyped(
                '1',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_1
                )
        );
    }

    public void buttonAction2(View view) {

        codeArea.getCommandHandler().keyTyped(
                '2',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_2
                )
        );
    }

    public void buttonAction3(View view) {

        codeArea.getCommandHandler().keyTyped(
                '3',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_3
                )
        );
    }

    public void buttonAction4(View view) {

        codeArea.getCommandHandler().keyTyped(
                '4',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_4
                )
        );
    }

    public void buttonAction5(View view) {

        codeArea.getCommandHandler().keyTyped(
                '5',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_5
                )
        );
    }

    public void buttonAction6(View view) {

        codeArea.getCommandHandler().keyTyped(
                '6',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_6
                )
        );
    }

    public void buttonAction7(View view) {

        codeArea.getCommandHandler().keyTyped(
                '7',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_7
                )
        );
    }

    public void buttonAction8(View view) {

        /*
         * FIX:
         * This was KEYCODE_0 in the original code.
         */
        codeArea.getCommandHandler().keyTyped(
                '8',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_8
                )
        );
    }

    public void buttonAction9(View view) {

        codeArea.getCommandHandler().keyTyped(
                '9',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_9
                )
        );
    }

    public void buttonActionA(View view) {

        codeArea.getCommandHandler().keyTyped(
                'a',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_A
                )
        );
    }

    public void buttonActionB(View view) {

        codeArea.getCommandHandler().keyTyped(
                'b',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_B
                )
        );
    }

    public void buttonActionC(View view) {

        codeArea.getCommandHandler().keyTyped(
                'c',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_C
                )
        );
    }

    public void buttonActionD(View view) {

        codeArea.getCommandHandler().keyTyped(
                'd',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_D
                )
        );
    }

    public void buttonActionE(View view) {

        codeArea.getCommandHandler().keyTyped(
                'e',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_E
                )
        );
    }

    public void buttonActionF(View view) {

        codeArea.getCommandHandler().keyTyped(
                'f',
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_F
                )
        );
    }

    public void buttonActionUp(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_UP
                )
        );
    }

    public void buttonActionDown(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_DOWN
                )
        );
    }

    public void buttonActionLeft(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT
                )
        );
    }

    public void buttonActionRight(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_RIGHT
                )
        );
    }

    public void buttonActionHome(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MOVE_HOME
                )
        );
    }

    public void buttonActionEnd(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MOVE_END
                )
        );
    }

    public void buttonActionDelete(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_FORWARD_DEL
                )
        );
    }

    public void buttonActionBk(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DEL
                )
        );
    }

    public void buttonActionTab(View view) {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_TAB
                )
        );
    }

    public void buttonActionPreviousMatch(View view) {

        SearchCodeAreaColorAssessor searchAssessor =
                CodeAreaAndroidUtils.findColorAssessor(
                        (ColorAssessorPainterCapable)
                                codeArea.getPainter(),
                        SearchCodeAreaColorAssessor.class
                );

        searchAssessor.setCurrentMatchIndex(
                searchAssessor.getCurrentMatchIndex() - 1
        );

        updateSearchStatusPanel(
                searchAssessor.getCurrentMatchIndex(),
                searchAssessor.getMatches().size()
        );

        SearchMatch currentMatch =
                searchAssessor.getCurrentMatch();

        if (currentMatch != null) {

            codeArea.revealPosition(
                    currentMatch.getPosition(),
                    0,
                    codeArea.getActiveSection()
            );

            codeArea.repaint();
        }
    }

    public void buttonActionNextMatch(View view) {

        SearchCodeAreaColorAssessor searchAssessor =
                CodeAreaAndroidUtils.findColorAssessor(
                        (ColorAssessorPainterCapable)
                                codeArea.getPainter(),
                        SearchCodeAreaColorAssessor.class
                );

        searchAssessor.setCurrentMatchIndex(
                searchAssessor.getCurrentMatchIndex() + 1
        );

        updateSearchStatusPanel(
                searchAssessor.getCurrentMatchIndex(),
                searchAssessor.getMatches().size()
        );

        SearchMatch currentMatch =
                searchAssessor.getCurrentMatch();

        if (currentMatch != null) {

            codeArea.revealPosition(
                    currentMatch.getPosition(),
                    0,
                    codeArea.getActiveSection()
            );

            codeArea.repaint();
        }
    }

    public void buttonActionHideSearchPanel(
            View view
    ) {

        if (binarySearch != null) {

            binarySearch.cancelSearch();
            binarySearch.clearSearch();
        }

        hideSearchStatusPanel();
    }

    public void cycleEditMode() {

        codeArea.getCommandHandler().keyPressed(
                new KeyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_INSERT
                )
        );
    }

    public void cycleEditMode(View view) {
        cycleEditMode();
    }

    public void goToPosition(View view) {

        GoToPositionDialog goToPositionDialog =
                new GoToPositionDialog();

        goToPositionDialog.setPositiveListener(
                (dialog, which) -> {

                    try {

                        DefaultCodeAreaCaretPosition
                                caretPosition =
                                new DefaultCodeAreaCaretPosition();

                        caretPosition.setCodeOffset(0);

                        caretPosition.setPosition(
                                codeArea
                                        .getActiveCaretPosition()
                        );

                        caretPosition.setDataPosition(
                                Math.min(
                                        goToPositionDialog
                                                .getTargetPosition(),
                                        codeArea.getDataSize()
                                )
                        );

                        codeArea.setActiveCaretPosition(
                                caretPosition
                        );

                        codeArea.validateCaret();
                        codeArea.centerOnCursor();

                    } catch (NumberFormatException ex) {

                        reportException(ex);
                    }
                }
        );

        goToPositionDialog.show(
                getSupportFragmentManager(),
                "goToPositionDialog"
        );
    }

    public void editSelection(View view) {

        EditSelectionDialog editSelectionDialog =
                new EditSelectionDialog();

        editSelectionDialog.setPositiveListener(
                (dialog, which) -> {

                    try {

                        Optional<SelectionRange>
                                selectionRange =
                                editSelectionDialog
                                        .getSelectionRange();

                        if (selectionRange.isPresent()) {

                            codeArea.setSelection(
                                    selectionRange.get()
                            );

                        } else {

                            codeArea.clearSelection();
                        }

                        codeArea.revealCursor();

                    } catch (NumberFormatException ex) {

                        reportException(ex);
                    }
                }
        );

        editSelectionDialog.show(
                getSupportFragmentManager(),
                "editSelectionDialog"
        );
    }

    @Nullable
    public BinarySearch getBinarySearch() {
        return binarySearch;
    }

    @Nullable
    public SearchParameters getSearchParameters() {

        ApplicationContext application =
                (ApplicationContext) getApplication();

        return application.getSearchParameters();
    }

    public void setSearchParameters(
            SearchParameters searchParameters
    ) {

        ApplicationContext application =
                (ApplicationContext) getApplication();

        application.setSearchParameters(
                searchParameters
        );
    }

    public BinarySearchService.@Nullable
    SearchStatusListener getSearchStatusListener() {

        return searchStatusListener;
    }

    @Nullable
    public CodeArea getCodeArea() {
        return codeArea;
    }

    private void requestWriteExternalStoragePermission() {

        final String[] permissions =
                new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                };

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )) {

            AlertDialog.Builder builder =
                    new AlertDialog.Builder(this);

            builder.setMessage(
                    R.string.storage_permission_request
            );

            builder.setPositiveButton(
                    R.string.button_request,
                    (dialog, which) ->
                            ActivityCompat.requestPermissions(
                                    HexEditorActivity.this,
                                    permissions,
                                    STORAGE_PERMISSION_CODE
                            )
            );

            builder.setNegativeButton(
                    R.string.button_cancel,
                    null
            );

            builder.show();

        } else {

            ActivityCompat.requestPermissions(
                    HexEditorActivity.this,
                    permissions,
                    STORAGE_PERMISSION_CODE
            );
        }
    }

    public static LocaleListCompat getLanguageLocaleList(
            String language
    ) {

        if (language.isEmpty()) {

            return LocaleListCompat.getEmptyLocaleList();
        }

        return LocaleListCompat.forLanguageTags(
                language
        );
    }

    @NullMarked
    private class CodeAreaKeyListener
            implements View.OnKeyListener {

        private final KeyListener keyListener =
                new TextKeyListener(
                        TextKeyListener.Capitalize.NONE,
                        false
                );

        private final Editable editable =
                Editable.Factory
                        .getInstance()
                        .newEditable("");

        public CodeAreaKeyListener() {

            editable.clear();

            Selection.setSelection(
                    editable,
                    0,
                    0
            );
        }

        @Override
        public boolean onKey(
                View view,
                int keyCode,
                KeyEvent keyEvent
        ) {

            if (editorDestroyed) {
                return true;
            }

            if (!codeArea.isFocused()) {

                if (keyEvent.getAction()
                        == KeyEvent.ACTION_UP) {

                    if (keyEvent.getKeyCode()
                            == KeyEvent.KEYCODE_BACK
                            && CompatUtils.isAndroidTV(
                                    HexEditorActivity.this
                            )) {

                        codeArea.post(
                                codeArea::requestFocus
                        );

                        return true;
                    }
                }

                View currentFocus =
                        getCurrentFocus();

                if (currentFocus != null) {

                    return currentFocus
                            .dispatchKeyEvent(
                                    keyEvent
                            );
                }

                return false;
            }

            try {

                if (keyEvent.getAction()
                        == KeyEvent.ACTION_DOWN) {

                    if (keyEvent.getKeyCode()
                            == KeyEvent.KEYCODE_DEL
                            || keyEvent.getKeyCode()
                            == KeyEvent.KEYCODE_FORWARD_DEL) {

                        editable.clear();

                        codeArea
                                .getCommandHandler()
                                .keyPressed(
                                        keyEvent
                                );

                    } else {

                        keyListener.onKeyDown(
                                view,
                                editable,
                                keyCode,
                                keyEvent
                        );

                        processKeys(
                                keyEvent
                        );
                    }

                } else if (keyEvent.getAction()
                        == KeyEvent.ACTION_UP) {

                    editable.clear();

                    if (keyEvent.getKeyCode()
                            == KeyEvent.KEYCODE_DPAD_CENTER) {

                        if (keyEvent.getEventTime()
                                - keyEvent.getDownTime()
                                > TimeUnit.SECONDS
                                .toMillis(1)) {

                            toolbar.showOverflowMenu();

                        } else {

                            codeArea.showContextMenu();
                        }

                    } else if (keyEvent.getKeyCode()
                            == KeyEvent.KEYCODE_BACK) {

                        if (fileHandler.isModified()) {

                            releaseFile(
                                    HexEditorActivity.this::finish
                            );

                        } else {

                            if (System.currentTimeMillis()
                                    - lastBackKeyPressTime
                                    < DOUBLE_BACK_KEY_INTERVAL) {

                                finish();

                            } else {

                                lastBackKeyPressTime =
                                        System.currentTimeMillis();

                                Toast.makeText(
                                        HexEditorActivity.this,
                                        getResources().getText(
                                                R.string.confirm_exit
                                        ),
                                        Toast.LENGTH_SHORT
                                ).show();
                            }
                        }

                    } else if (keyEvent.getKeyCode()
                            != KeyEvent.KEYCODE_DEL
                            && keyEvent.getKeyCode()
                            != KeyEvent.KEYCODE_FORWARD_DEL) {

                        codeArea
                                .getCommandHandler()
                                .keyPressed(
                                        keyEvent
                                );
                    }

                } else {

                    keyListener.onKeyOther(
                            view,
                            editable,
                            keyEvent
                    );

                    processKeys(
                            keyEvent
                    );
                }

                return true;

            } catch (Exception ignored) {
            }

            return false;
        }

        private void processKeys(
                KeyEvent keyEvent
        ) {

            int outputCharsLength =
                    editable.length();

            if (outputCharsLength > 0) {

                for (int i = 0;
                     i < outputCharsLength;
                     i++) {

                    codeArea
                            .getCommandHandler()
                            .keyTyped(
                                    editable.charAt(i),
                                    keyEvent
                            );
                }

                editable.clear();
            }
        }
    }

    private void showAboutDialog() {

        String message =
                "BinEd – Binary / Hexadecimal Editor\n\n"
                        + "Este aplicativo utiliza componentes "
                        + "do projeto BinEd.\n\n"
                        + "Copyright (C) ExBin Project\n"
                        + "Website: https://exbin.org\n\n"
                        + "Licenciado sob a Apache License, "
                        + "Version 2.0.\n\n"
                        + "Copyright e créditos do projeto "
                        + "original preservados.";

        new AlertDialog.Builder(this)
                .setTitle("Sobre o BinEd")
                .setMessage(message)
                .setPositiveButton(
                        "OK",
                        null
                )
                .setNeutralButton(
                        "LICENÇA",
                        (dialog, which) -> {

                            try {

                                Intent intent =
                                        new Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(
                                                        "https://www.apache.org/licenses/LICENSE-2.0"
                                                )
                                        );

                                startActivity(intent);

                            } catch (Exception ignored) {
                            }
                        }
                )
                .show();
    }

    private void reportException(
            Throwable exception
    ) {

        if (editorDestroyed) {
            return;
        }

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this);

        builder.setTitle(
                R.string.error_exception
        );

        if (exception instanceof OutOfMemoryError) {

            builder.setMessage(
                    "OutOfMemoryError: "
                            + exception.getLocalizedMessage()
            );

        } else {

            builder.setMessage(
                    exception.getLocalizedMessage()
            );
        }

        builder.setNegativeButton(
                R.string.button_close,
                null
        );

        AlertDialog alertDialog =
                builder.create();

        alertDialog.show();
    }

    public enum FallbackFileType {
        FILE,
        TABLE_FILE
    }

    private String getCurrentFileName() {

        if (suggestedFileName != null
                && !suggestedFileName
                .trim()
                .isEmpty()) {

            return suggestedFileName;
        }

        Uri uri =
                fileHandler.getCurrentFileUri();

        if (uri != null) {

            String name =
                    getDisplayName(uri);

            if (name != null
                    && !name.trim().isEmpty()) {

                if (!name
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".bin")) {

                    name =
                            "Hex_" + name;
                }

                return name;
            }
        }

        return "arquivo.bin";
    }

    private String getDisplayName(
            Uri uri
    ) {

        if (uri == null) {
            return null;
        }

        try (
                android.database.Cursor cursor =
                        getContentResolver().query(
                                uri,
                                new String[]{
                                        OpenableColumns.DISPLAY_NAME
                                },
                                null,
                                null,
                                null
                        )
        ) {

            if (cursor != null
                    && cursor.moveToFirst()) {

                int index =
                        cursor.getColumnIndex(
                                OpenableColumns.DISPLAY_NAME
                        );

                if (index >= 0) {

                    return cursor.getString(
                            index
                    );
                }
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    private final SelectionChangedListener
        menuSelectionChangedListener =
        this::updateEditActionsState;

}
