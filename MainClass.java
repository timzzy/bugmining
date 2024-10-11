package testartifact;

import org.json.JSONArray;
import org.json.JSONObject;

import org.eclipse.jgit.lib.Repository;
import org.refactoringminer.api.GitHistoryRefactoringMiner;
import org.refactoringminer.api.GitService;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.refactoringminer.util.GitServiceImpl;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class MainClass {
    private static final String GITHUB_TOKEN = "";
    private static final String OUTPUT_DIR = "output";
    private static final long ACTIVITY_TIMEOUT_MINUTES = 90; // Consider process hung if no activity for 15 minutes
    private static AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    public static void main(String[] args) throws Exception {
        JSONArray topRepos = getTopJavaRepositories(100);
        
        List<JSONObject> repositoriesToProcess = analyzeRepositories(topRepos);
        
        if (repositoriesToProcess.isEmpty()) {
            System.out.println("\nNo new repositories to process. Exiting program.");
            return;
        }
        
        processRepositories(repositoriesToProcess);
    }

    private static void processRepositories(List<JSONObject> repositoriesToProcess) throws InterruptedException {
        System.out.println("\n=== Starting Refactoring Detection ===");
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GitService gitService = new GitServiceImpl();
        GitHistoryRefactoringMiner miner = new GitHistoryRefactoringMinerImpl();

        for (int i = 0; i < repositoriesToProcess.size(); i++) {
            JSONObject repo = repositoriesToProcess.get(i);
            String repoName = repo.getString("full_name");
            String cloneUrl = repo.getString("clone_url");

            System.out.println(String.format("\nProcessing repository %d of %d: %s", 
                i + 1, repositoriesToProcess.size(), repoName));

            try {
                Repository localRepo = gitService.cloneIfNotExists("tmp/" + repoName, cloneUrl);
                
                // Reset the activity timer
                lastActivityTime.set(System.currentTimeMillis());
                
                // Create a future for the mining task
                Future<?> miningTask = executor.submit(() -> {
                    try {
                        miner.detectAll(localRepo, "master", new RefactoringHandler() {
                            @Override
                            public void handle(String commitId, List<Refactoring> refactorings) {
                                // Update activity timestamp
                                lastActivityTime.set(System.currentTimeMillis());
                                System.out.println("Refactorings found at commit " + commitId);
                                saveRefactoringsToJson(refactorings, repoName, commitId + "_refactorings.json");
                            }

                            @Override
                            public void handleException(String commitId, Exception e) {
                                // Update activity timestamp even for exceptions
                                lastActivityTime.set(System.currentTimeMillis());
                                System.err.println("Error processing commit " + commitId + ": " + e.getMessage());
                            }
                        });
                        return null;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                // Create a monitoring task
                ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
                ScheduledFuture<?> monitorTask = monitor.scheduleAtFixedRate(() -> {
                    long timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime.get();
                    if (timeSinceLastActivity > ACTIVITY_TIMEOUT_MINUTES * 60 * 1000) {
                        System.err.println("WARNING: No activity detected for " + ACTIVITY_TIMEOUT_MINUTES + 
                            " minutes. Considering process hung for repository: " + repoName);
                        miningTask.cancel(true);
                        monitor.shutdown();
                    }
                }, 1, 1, TimeUnit.MINUTES);

                try {
                    miningTask.get(); // Wait indefinitely, relying on activity monitor for timeout
                    monitorTask.cancel(false);
                    System.out.println("Successfully processed repository: " + repoName);
                } catch (CancellationException e) {
                    System.err.println("Processing cancelled due to inactivity for repository: " + repoName);
                } catch (ExecutionException e) {
                    System.err.println("ERROR: Failed to process repository: " + repoName);
                    e.getCause().printStackTrace();
                } finally {
                    monitor.shutdownNow();
                }

            } catch (Exception e) {
                System.err.println("ERROR: Failed to clone repository: " + repoName);
                e.printStackTrace();
            }

            // Add a delay before moving to the next repository
            if (i < repositoriesToProcess.size() - 1) {
                System.out.println("Waiting for 60 seconds before processing the next repository...\n");
                Thread.sleep(60000);
            }
        }
        
        executor.shutdownNow();
        System.out.println("\n=== Refactoring Detection Completed ===");
    }

    private static List<JSONObject> analyzeRepositories(JSONArray topRepos) {
        System.out.println("=== Analyzing Repositories ===");
        System.out.println(String.format("Total repositories to check: %d", topRepos.length()));
        
        List<JSONObject> repositoriesToProcess = new ArrayList<>();
        List<JSONObject> alreadyProcessedRepos = new ArrayList<>();
        
        for (int i = 0; i < topRepos.length(); i++) {
            JSONObject repo = topRepos.getJSONObject(i);
            String repoName = repo.getString("full_name");
            
            System.out.println(String.format("\nChecking repository %d of %d: %s", i + 1, topRepos.length(), repoName));
            printRepoDetails(repo);
            
            if (isRepositoryAlreadyProcessed(repoName)) {
                System.out.println("STATUS: Already processed (found in local system)");
                alreadyProcessedRepos.add(repo);
            } else {
                System.out.println("STATUS: Needs processing (not found in local system)");
                repositoriesToProcess.add(repo);
            }
        }
        
        printProcessingSummary(alreadyProcessedRepos, repositoriesToProcess);
        return repositoriesToProcess;
    }

    // ... [All other helper methods remain the same as in the previous version]
    
    private static void printProcessingSummary(List<JSONObject> alreadyProcessedRepos, 
                                              List<JSONObject> repositoriesToProcess) {
        System.out.println("\n=== Processing Summary ===");
        System.out.println("Repositories already processed: " + alreadyProcessedRepos.size());
        System.out.println("Repositories needing processing: " + repositoriesToProcess.size());
        
        if (!alreadyProcessedRepos.isEmpty()) {
            System.out.println("\nAlready Processed Repositories:");
            for (JSONObject repo : alreadyProcessedRepos) {
                System.out.println("- " + repo.getString("full_name"));
            }
        }
    }

    private static boolean isRepositoryAlreadyProcessed(String repoName) {
        Path repoOutputPath = Paths.get(OUTPUT_DIR, repoName);
        File repoOutputDir = repoOutputPath.toFile();
        
        if (!repoOutputDir.exists() || !repoOutputDir.isDirectory()) {
            return false;
        }
        
        File[] files = repoOutputDir.listFiles();
        return files != null && files.length > 0;
    }

    private static void printRepoDetails(JSONObject repo) {
        System.out.println("   Stars: " + repo.getInt("stargazers_count"));
        System.out.println("   Description: " + repo.optString("description", "No description available"));
        //System.out.println("   URL: " + repo.getString("html_url"));
        //System.out.println("   Clone URL: " + repo.getString("clone_url"));
        //System.out.println("   Default Branch: " + repo.getString("default_branch"));
        System.out.println("   Has Issues: " + repo.getBoolean("has_issues"));
        //System.out.println("   Open Issues Count: " + repo.getInt("open_issues_count"));
    }

    private static JSONArray getTopJavaRepositories(int topN) throws IOException {
        String apiUrl = "https://api.github.com/search/repositories?q=language:Java+framework+stars:>1000+NOT+machine-learning+NOT+deep-learning+NOT+neural-network+NOT+AI+NOT+artificial-intelligence&sort=stars&order=desc&per_page=" + topN;
        JSONObject jsonResponse = new JSONObject(fetchDataFromGitHub(apiUrl));
        return jsonResponse.getJSONArray("items");
    }

    private static String fetchDataFromGitHub(String apiUrl) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);

        Scanner scanner = new Scanner(connection.getInputStream());
        String response = scanner.useDelimiter("\\Z").next();
        scanner.close();

        return response;
    }

    private static void saveRefactoringsToJson(List<Refactoring> refactorings, String repoName, String filename) {
        JSONArray jsonArray = new JSONArray();
        for (Refactoring ref : refactorings) {
            jsonArray.put(new JSONObject(ref.toJSON()));
        }

        saveJsonToFile(jsonArray, repoName, filename);
    }

    private static void saveJsonToFile(JSONArray jsonArray, String repoName, String filename) {
        try {
            File directory = new File(OUTPUT_DIR + "/" + repoName);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            try (FileWriter file = new FileWriter(OUTPUT_DIR + "/" + repoName + "/" + filename)) {
                file.write(jsonArray.toString(4));
                System.out.println("Saved data to " + OUTPUT_DIR + "/" + repoName + "/" + filename);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}