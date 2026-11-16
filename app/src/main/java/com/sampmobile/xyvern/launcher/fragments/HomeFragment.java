package com.sampmobile.xyvern.launcher.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.downloader.PRDownloader;
import com.downloader.PRDownloaderConfig;
import com.joom.paranoid.Obfuscate;
import com.sampmobile.xyvern.R;
import com.sampmobile.xyvern.game.GameDataDownloadService;
import com.sampmobile.xyvern.game.SAMP;
import com.sampmobile.xyvern.launcher.MainActivity;
import com.sampmobile.xyvern.launcher.util.GameDataVerifier;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Obfuscate
public class HomeFragment extends Fragment {

    // National Street RP server configuration.
    private static final String SERVER_IP = "142.132.203.47";
    private static final int SERVER_PORT = 16234;

    // Keep these preferences identical to PlayFragment and SAMP.java.
    private static final String PREF_NAME = "com.sampmobile.xyvern";
    private static final String PREF_NICKNAME = "nickname";

    // Existing game-data download URL.
    private static final String DATA_ZIP_URL =
            "https://github.com/rezzylrn/samp-mobile-optimized/releases/download/data-v1/files.zip";

    private View dotStatus;
    private TextView tvDataTitle;
    private TextView tvDataDesc;
    private TextView tvManualPath;
    private ProgressBar pbDownload;
    private TextView tvDownloadProgress;
    private Button btnVerifyData;
    private Button btnDownloadData;

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isDownloading = false;
    private volatile GameDataDownloadService.Listener listener;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        // A Fragment view can be recreated, so create a fresh executor each time.
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideKeyboard(getActivity());
        }

        dotStatus = view.findViewById(R.id.dot_status);
        tvDataTitle = view.findViewById(R.id.tv_data_title);
        tvDataDesc = view.findViewById(R.id.tv_data_desc);
        tvManualPath = view.findViewById(R.id.tv_manual_path);
        pbDownload = view.findViewById(R.id.pb_download);
        tvDownloadProgress = view.findViewById(R.id.tv_download_progress);
        btnVerifyData = view.findViewById(R.id.btn_verify_data);
        btnDownloadData = view.findViewById(R.id.btn_download_data);

        Context context = requireContext();
        File externalDir = context.getExternalFilesDir(null);
        if (externalDir != null) {
            tvManualPath.setText(externalDir.getAbsolutePath());
        }

        try {
            PRDownloader.initialize(
                    context.getApplicationContext(),
                    PRDownloaderConfig.newBuilder()
                            .setDatabaseEnabled(true)
                            .setReadTimeout(30000)
                            .setConnectTimeout(30000)
                            .build()
            );
        } catch (Exception ignored) {
            // PRDownloader may already be initialized.
        }

        btnVerifyData.setOnClickListener(v -> runVerification());

        runVerification();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isDownloading && getView() != null) {
            runVerification();
        }
    }

    /**
     * Checks the installed game files without blocking the main UI thread.
     */
    private void runVerification() {
        if (!isAdded() || getView() == null || executor == null || executor.isShutdown()) {
            return;
        }

        final Context appContext = requireContext().getApplicationContext();

        tvDataDesc.setText("Verifying game files...");
        btnVerifyData.setEnabled(false);

        try {
            executor.execute(() -> {
                final GameDataVerifier.VerificationResult result;

                try {
                    result = GameDataVerifier.verifyGameData(appContext);
                } catch (Exception exception) {
                    mainHandler.post(() -> {
                        if (!isAdded() || getView() == null) return;

                        btnVerifyData.setEnabled(true);
                        tvDataTitle.setText("Verification Error");
                        tvDataDesc.setText(
                                "Could not verify game files: " + exception.getMessage()
                        );
                        Toast.makeText(
                                requireContext(),
                                "Game data verification failed.",
                                Toast.LENGTH_LONG
                        ).show();
                    });
                    return;
                }

                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;

                    btnVerifyData.setEnabled(true);

                    if (result.isValid) {
                        showGameDataReady(result.totalSizeBytes);
                    } else {
                        showGameDataIncomplete(result.missingFiles.size());
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The Fragment view is being destroyed; a later view will create a new executor.
        }
    }

    private void showGameDataReady(long totalSizeBytes) {
        dotStatus.setBackgroundResource(R.drawable.bg_dot_green);
        tvDataTitle.setText("National Street RP Ready");
        tvDataDesc.setText(
                "All core GTA SA and SAMP files are verified ("
                        + (totalSizeBytes / 1048576)
                        + " MB). Tap PLAY NOW to connect."
        );

        btnVerifyData.setVisibility(View.GONE);
        btnDownloadData.setVisibility(View.VISIBLE);
        btnDownloadData.setEnabled(true);
        btnDownloadData.setText("PLAY NOW");

        // Always ask for a nickname, even when one was saved previously.
        btnDownloadData.setOnClickListener(v -> showNicknameDialog());
    }

    private void showGameDataIncomplete(int missingFileCount) {
        dotStatus.setBackgroundResource(R.drawable.bg_dot_red);
        tvDataTitle.setText("Game Data Incomplete");
        tvDataDesc.setText(
                "Verification failed (" + missingFileCount
                        + " files missing).\n"
                        + "Download the asset package or copy the files manually."
        );

        btnVerifyData.setVisibility(View.VISIBLE);
        btnVerifyData.setText("Verify");
        btnDownloadData.setVisibility(View.VISIBLE);
        btnDownloadData.setEnabled(true);
        btnDownloadData.setText("Download Assets");

        btnDownloadData.setOnClickListener(v -> {
            if (isDownloading) {
                Toast.makeText(
                        requireContext(),
                        "Download in progress...",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                startOnlineDataDownload();
            }
        });
    }

    /**
     * Shows the nickname dialog every time PLAY NOW is pressed.
     */
    private void showNicknameDialog() {
        if (!isAdded() || getContext() == null) return;

        SharedPreferences preferences = requireContext().getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        );

        EditText nicknameInput = new EditText(requireContext());
        nicknameInput.setSingleLine(true);
        nicknameInput.setHint("Enter your nickname");
        nicknameInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        nicknameInput.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(24)
        });

        String savedNickname = preferences.getString(PREF_NICKNAME, "");
        nicknameInput.setText(savedNickname);
        nicknameInput.setSelection(nicknameInput.getText().length());

        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        nicknameInput.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("NATIONAL STREET RP")
                .setMessage("Enter your nickname to connect to the server.")
                .setView(nicknameInput)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONNECT", null)
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String nickname = nicknameInput.getText().toString().trim();

                    if (nickname.isEmpty()) {
                        nicknameInput.setError("Nickname is required");
                        return;
                    }

                    // SAMP.java uses a maximum nickname length of 24 characters.
                    if (nickname.length() > 24) {
                        nicknameInput.setError("Maximum 24 characters");
                        return;
                    }

                    preferences.edit()
                            .putString(PREF_NICKNAME, nickname)
                            .apply();

                    dialog.dismiss();
                    verifyAndConnect();
                })
        );

        dialog.show();
    }

    /**
     * Re-checks game data immediately before launching SAMP.
     */
    private void verifyAndConnect() {
        if (!isAdded() || getView() == null || executor == null || executor.isShutdown()) {
            return;
        }

        final Context appContext = requireContext().getApplicationContext();

        btnDownloadData.setEnabled(false);
        tvDataDesc.setText("Verifying game files before connecting...");

        try {
            executor.execute(() -> {
                final GameDataVerifier.VerificationResult result;

                try {
                    result = GameDataVerifier.verifyGameData(appContext);
                } catch (Exception exception) {
                    mainHandler.post(() -> {
                        if (!isAdded() || getView() == null) return;

                        btnDownloadData.setEnabled(true);
                        Toast.makeText(
                                requireContext(),
                                "Game verification failed: " + exception.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                        runVerification();
                    });
                    return;
                }

                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;

                    btnDownloadData.setEnabled(true);

                    if (!result.isValid) {
                        Toast.makeText(
                                requireContext(),
                                "Game data is incomplete. Download the required files first.",
                                Toast.LENGTH_LONG
                        ).show();
                        showGameDataIncomplete(result.missingFiles.size());
                        return;
                    }

                    Intent intent = new Intent(requireActivity(), SAMP.class);
                    intent.putExtra("ip", SERVER_IP);
                    intent.putExtra("port", SERVER_PORT);
                    startActivity(intent);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The Fragment is leaving; do not attempt to launch the game.
        }
    }

    /**
     * Starts the existing resumable download service and observes its progress.
     */
    private void startOnlineDataDownload() {
        if (!isAdded() || getContext() == null || isDownloading) return;

        isDownloading = true;
        pbDownload.setVisibility(View.VISIBLE);
        tvDownloadProgress.setVisibility(View.VISIBLE);
        pbDownload.setIndeterminate(false);
        pbDownload.setMax(100);
        pbDownload.setProgress(0);
        tvDownloadProgress.setText("0%");
        tvDataDesc.setText("Connecting to asset server...");
        btnDownloadData.setEnabled(false);

        GameDataDownloadService.Listener newListener = status -> {
            if (!isAdded()) return;

            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) return;

                if (status.phase == GameDataDownloadService.PHASE_EXTRACT) {
                    pbDownload.setProgress(status.percent);
                    tvDownloadProgress.setText(
                            "Extracting " + status.percent + "% ("
                                    + status.bytes + "/" + status.total + ")"
                    );

                } else if (status.phase == GameDataDownloadService.PHASE_DONE) {
                    isDownloading = false;
                    pbDownload.setVisibility(View.GONE);
                    tvDownloadProgress.setVisibility(View.GONE);
                    btnDownloadData.setEnabled(true);

                    Toast.makeText(
                            requireContext(),
                            "Game assets successfully installed!",
                            Toast.LENGTH_LONG
                    ).show();

                    if (listener != null) {
                        GameDataDownloadService.removeListener(listener);
                        listener = null;
                    }

                    runVerification();

                } else if (status.phase == GameDataDownloadService.PHASE_FAILED) {
                    isDownloading = false;
                    pbDownload.setVisibility(View.GONE);
                    tvDownloadProgress.setVisibility(View.GONE);
                    btnDownloadData.setEnabled(true);
                    tvDataDesc.setText(
                            "Download error: " + status.error
                                    + "\nTap Download Assets to retry."
                    );

                    if (listener != null) {
                        GameDataDownloadService.removeListener(listener);
                        listener = null;
                    }

                } else if (status.phase == GameDataDownloadService.PHASE_DOWNLOAD) {
                    pbDownload.setProgress(status.percent);
                    tvDownloadProgress.setText(
                            status.percent + "% ("
                                    + (status.bytes / 1048576)
                                    + "/" + (status.total / 1048576)
                                    + " MB)"
                    );
                }
            });
        };

        listener = newListener;
        GameDataDownloadService.addListener(newListener);

        GameDataDownloadService.start(
                requireContext(),
                GameDataDownloadService.ACTION_ASSETS,
                DATA_ZIP_URL,
                "assets",
                666478908L,
                ""
        );
    }

    @Override
    public void onDestroyView() {
        if (listener != null) {
            GameDataDownloadService.removeListener(listener);
            listener = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        super.onDestroyView();
    }
                    }
