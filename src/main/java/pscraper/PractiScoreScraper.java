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

/**
 *
 * @author Asmod
 */
public class PractiScoreScraper implements Runnable {

    private String url      = "";
    private String fileName = "";

    private int numStages    = 0;
    private int MAX_HANDLERS = 5;
    private int stageNum     = 1;
    private int division     = 1;

    private float shootersProcessed = 0;

    private boolean headless = true;

    private File logFile;
    private FileWriter fwriter;

    private String divisionStr;
    private String fullDivName;

    private Browser browser;

    private List<ProgressHandler> progressHandlers;
    private List<StateHandler>    stateHandlers;

    private final int PSBL_IDX   = 1;
    private final int STGPTS_IDX = 2;
    private final int PTS_IDX    = 3;
    private final int HF_IDX     = 4;
    private final int TIME_IDX   = 5;
    private final int DIV_IDX    = 6;
    private final int A_IDX      = 11;
    private final int B_IDX      = 12;
    private final int C_IDX      = 13;
    private final int D_IDX      = 14;
    private final int M_IDX      = 15;
    private final int NPM_IDX    = 17;
    private final int NS_IDX     = 18;
    private final int PROC_IDX   = 19;

    private List<Integer> metricsIndices;
    private List<Double> metrics;
            
    public PractiScoreScraper (String url, String division, int numStages, String fileName, boolean headless) {
        
        this.url         = url;
        this.fileName    = fileName;
        this.numStages   = numStages;
        this.headless    = headless;
        this.divisionStr = division;

        progressHandlers = new ArrayList<>();
        stateHandlers    = new ArrayList<>();
        metrics          = new ArrayList<>();

        populateMetricIndices();

        detectDivision ();
    }
    
    @Override
    public void run () {

        /* Do the PlayWright Magic */
        emitProgress(0);

        createLogFile(fileName);

        emitState("Log file created.");

        Playwright pWright = Playwright.create();

        emitState("Launching browser.");

        browser = pWright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(headless));

        emitState("Loading page.");

        Page page = browser.newPage();

        page.setDefaultTimeout(120000);
        
        page.navigate(url);

        page.locator("#divisionLevel").selectOption(Integer.toString(division));

        delay(10);

        for(stageNum = 1; stageNum < numStages + 1; stageNum++) {

            page.locator("#resultLevel").selectOption(Integer.toString(stageNum));

            delay(10);
            
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

            int rowCount   = rows.count();

            
            for(int idx = 0; idx < rowCount-1; idx++) {

                emitState(String.format("Capturing shooter %d/%d in Stage %d", idx+1, rowCount-1, stageNum));

                metrics.clear();

                /* The first row has nothing in it. */
                Locator row = rows.nth(idx+1);

                Locator cells = row.locator("td");

                boolean valid = false;

                /* Throw out shooters not in the selected division and overall results */
                if (!cells.nth(DIV_IDX).textContent().contains(fullDivName) || division == 0) {
                    shootersProcessed++;
                    continue;
                }

                for (int cellIdx : metricsIndices) {

                    String cellText = cells.nth(cellIdx).textContent();
                    
                    /* Null row protection */
                    if(!isNumeric(cellText)){
                        break;
                    }

                    /* 0 HF Rejection */
                    if(cellIdx == PSBL_IDX && Double.parseDouble(cellText) <= 0) {
                        break;
                    }

                    metrics.add(Double.valueOf(cellText));

                    valid = true;

                }

                /* Write the data and update the progress bar */
                if(valid) {
                    writeMetricData(metrics);
                }

                shootersProcessed++;

                float rowCountf  = (float) rowCount;
                float numStagesf = (float) numStages;

                emitProgress(Math.round((shootersProcessed/(numStagesf*rowCountf))*100));
            }
        }

        /* We're done! */
        emitProgress(100);
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

                fwriter.write("%psbl,StagePoints,HF,Time,A,B,C,D,M,NPM,NS,Proc\n");

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

    private void populateMetricIndices () {
        metricsIndices = new ArrayList<>();

        metricsIndices.add(PSBL_IDX);
        metricsIndices.add(STGPTS_IDX);
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
