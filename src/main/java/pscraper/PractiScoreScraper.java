package pscraper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import Events.ProgressEvt;
import Events.StateEvt;
import Handlers.ProgressHandler;
import Handlers.StateHandler;

/**
 *
 * @author Asmod
 * 
 * @brief This is the main scraping logic for PractiScore data.
 * 
 *  TO-DO: One Hot Encode the division, and save that off as
 *         well for more robust ML models.
 */
public class PractiScoreScraper implements Runnable {

    private String url      = "";
    private String fileName = "";


    private int division     = 1;

    private boolean headless = true;

    private File logFile;
    public  FileWriter fwriter;

    private String divisionStr;
    private String fullDivName;

    private Browser browser;

    private List<ProgressHandler> progressHandlers;
    private List<StateHandler>    stateHandlers;

    private final int PSBL_IDX   = 1;
    private final int MAXPTS_IDX = 2;
    private final int PTS_IDX    = 3;
    private final int HF_IDX     = 4;
    private final int TIME_IDX   = 5;
    private final int DIV_IDX    = 6;
    private final int A_IDX      = 11;
    private final int B_IDX      = 12;
    private final int C_IDX      = 13;
    private final int D_IDX      = 14;
    private final int M_IDX      = 15;
    private final int NPM_IDX    = 16;
    private final int NS_IDX     = 17;
    private final int PROC_IDX   = 18;

    private List<Integer> metricsIndices;
    private List<Double> metrics;
    private List<Match>  matches;
            
    public PractiScoreScraper (List<Match> matches, String division, boolean headless) {
        
        this.headless    = headless;
        this.divisionStr = division;
        this.matches     = matches;

        progressHandlers = new ArrayList<>();
        stateHandlers    = new ArrayList<>();
        metrics          = new ArrayList<>();

        populateMetricIndices();

        detectDivision ();
    }
    
    @Override
    public void run () {

        int totalMatches = matches.size();

        /* Do the PlayWright Magic */
        emitProgress(0);

        for(int matchCntr = 0; matchCntr < totalMatches; matchCntr++) {

            Match match = matches.get(matchCntr);

            float shootersProcessed = 0;

            int stageNum;
            
            /* Create the log file */
            createLogFile(match.fileName);
            
            emitState("Log file created.");
            
            /* Launch the browser and load the match page */
            Playwright pWright = Playwright.create();
            
            emitState("Launching browser.");
            
            browser = pWright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(headless));
            
            emitState("Loading page.");
            
            Page page = browser.newPage();
            
            page.setDefaultTimeout(120000);
            
            page.navigate(match.url);
            
            /* Select the appropriate division */
            page.locator("#divisionLevel").selectOption(Integer.toString(division));
        
            /* Get number of stages */
            int numStages = page.locator("#resultLevel").locator("option").count();
    
            /* Loop over stages collecting data. Stage 0 contains match results (irrelevant) */
            for(stageNum = 1; stageNum < numStages; stageNum++) {
    
                /* Select the nth stage */
                page.locator("#resultLevel").selectOption(Integer.toString(stageNum));
                
                /* Wait for the results table to load, and grab the row count, rows, and max score for the stage */
                emitState("Searching for results table. . .");
                /* Wait up to 2 minutes to find the table. */
                try {
                    page.waitForSelector("#mainResultsTable", 
                                            new Page.WaitForSelectorOptions().setTimeout(120000));
                } catch (Exception e) {
                    cleanAbort("Timed out acquiring data.");
                }
    
                Locator table = page.locator("#mainResultsTable");
    
                Locator rows = table.locator("tr");
    
                /* The max score is contained in the Stage Points field for the top scorer */
                double maxPoints = Double.parseDouble(rows.nth(1).locator("td").nth(MAXPTS_IDX).textContent());

                int rowCount   = rows.count();
    
                float rowCountf;
                float numStagesf;
    
                boolean valid;
                
                /* Scrape the row data and write it to file */
                for(int idx = 0; idx < rowCount-1; idx++) {
    
                    emitState(String.format("Capturing shooter %d/%d in Stage %d/%d (Match %d/%d)", idx+1, rowCount-1, stageNum, numStages-1, matchCntr+1, totalMatches));
    
                    metrics.clear();
    
                    /* The first row has nothing in it. */
                    Locator row = rows.nth(idx+1);
    
                    Locator cells = row.locator("td");
    
                    valid = false;
    
                    /* Throw out shooters not in the selected division and overall results */
                    if (!cells.nth(DIV_IDX).textContent().contains(fullDivName) || division == 0) {
                        shootersProcessed++;
                        continue;
                    }
    
                    for (int cellIdx : metricsIndices) {
    
                        String cellText = cells.nth(cellIdx).textContent();
    
                        valid = false;
                        
                        /* Null row protection */
                        if(!isNumeric(cellText)){
                            break;
                        }
    
                        /* 0 HF Rejection */
                        if(cellIdx == PSBL_IDX && Double.parseDouble(cellText) <= 0) {
                            break;
                        }
    
                        /* Chrono Station Rejection */
                        if(cellIdx == PTS_IDX && Double.parseDouble(cellText) <= 0 ) {
                            break;
                        }
    
                        /* Unfortunately we must throw out time limited stages since PS doesn't record their time */
                        if(cellIdx == TIME_IDX && Double.parseDouble(cellText) <= 0 ) {
                            break;
                        }
    
                        /* Max points is constant for every shooter, the other metrics are not. */
                        if (cellIdx == MAXPTS_IDX) {
                            metrics.add(maxPoints);
                        } else {
                            metrics.add(Double.valueOf(cellText));
                        }
    
                        valid = true;
    
                    }
    
                    /* Write the data and update the progress bar */
                    if(valid) {
                        writeMetricData(metrics);
                    }
    
                    shootersProcessed++;
    
                    rowCountf  = (float) rowCount;
                    numStagesf = (float) numStages - 1;
    
                    emitProgress(Math.round((shootersProcessed/(numStagesf*rowCountf))*100));
                }
            }

            browser.close();
            closeLogFile();
            emitProgress(100);
        }

        /* We're done! */
        cleanAbort("COMPLETE");

    }

    public void addStateHandler (StateHandler stateHandler) {
        stateHandlers.add(stateHandler);
    }

    public void addProgressHandler (ProgressHandler progressHandler) {
        progressHandlers.add(progressHandler);
    }

    private void emitProgress (int progress) {

        ProgressEvt pEvt = new ProgressEvt(progress);

        for (ProgressHandler pHandler : progressHandlers) {
            pHandler.handleProgressEvt(pEvt);
        }

    }

    private void emitState (String msg) {

        StateEvt sEvt = new StateEvt(msg);

        for (StateHandler sHandler : stateHandlers) {
            sHandler.handleStateEvt(sEvt);
        }
    }

    private void createLogFile (String fileName) {

        logFile = new File(String.format("./%s.csv", fileName));

        if(!logFile.exists()) {
            try {
                if(!logFile.createNewFile()) {
                    cleanAbort("Unable to create log file.");
                }
            } catch (Exception e) {
                cleanAbort("Unable to create log file.");
            }

            /* The log file is new, write the header */
            try {
                fwriter = new FileWriter(logFile);

                fwriter.write("%psbl,MaxPoints,Points,HF,Time,A,B,C,D,M,NPM,NS,Proc\n");

            } catch (Exception e) {
                cleanAbort("Unable to create log file.");
            }
        } else {

            /* The file exists. Append to it. */
            try {
                fwriter = new FileWriter(logFile);
            } catch (IOException e) {
                cleanAbort("Failed to write entry to file.");
            }
        }
    }

    private void closeLogFile () {
        try {
            fwriter.close();
        } catch (Exception e) {
        }
    }

    private void populateMetricIndices () {
        metricsIndices = new ArrayList<>();

        metricsIndices.add(PSBL_IDX);
        metricsIndices.add(MAXPTS_IDX);
        metricsIndices.add(PTS_IDX);
        metricsIndices.add(HF_IDX);
        metricsIndices.add(TIME_IDX);
        metricsIndices.add(A_IDX);
        metricsIndices.add(B_IDX);
        metricsIndices.add(C_IDX);
        metricsIndices.add(D_IDX);
        metricsIndices.add(M_IDX);
        metricsIndices.add(NPM_IDX);
        metricsIndices.add(NS_IDX);
        metricsIndices.add(PROC_IDX);
    }

    private void detectDivision () {

        switch (divisionStr) {

            case ("CO"):
                division = 1;
                fullDivName = "Carry Optics";
                break;

            case ("L"):
                division = 2;
                fullDivName = "Limited";
                break;

            case ("LO"):
                division = 3;
                fullDivName = "Limited Optics";
                break;

            case ("O"):
                division = 4;
                fullDivName = "Open";
                break;

            case ("PCC"):
                division = 5;
                fullDivName = "PCC";
                break;

            case ("P"):
                division = 6;
                fullDivName = "Production";
                break;

            case ("SS"):
                division = 7;
                fullDivName = "Single Stack";
                break;

            /* Overall */
            default:
                division = 0;
                break;
        }


    }

    private boolean isNumeric(String strNum) {
        
        if (strNum == null) {
            return false;
        }
        try {
            double d = Double.parseDouble(strNum);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    private void writeMetricData(List<Double> metrics) {

        String outpString = "";

        for(Double metric : metrics) {
            outpString = outpString.concat(String.format("%f,", metric));
        }

        /* Replace the last comma with a newline  */
        outpString = outpString.substring(0, outpString.length() -1) + "\n";
        
        try {
            fwriter.write(outpString);
        } catch (IOException e) {
            cleanAbort("Failed to write entry to file.");
        }

    }

    private void cleanAbort (String message) {

        browser.close();
        
        try {
            fwriter.close();
        } catch (IOException e) {}

        if (message.contains("COMPLETE")) {
            emitState(message);
        } else {
            emitState(message);
            emitState("KILL");
        }
    }

    private void delay (float time) {

        long timeMilis = (long) (time*1000);

        try {
            Thread.sleep(timeMilis);
        } catch (Exception e) {
        }

    }
}
