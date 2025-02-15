package pscraper;

public class Match {

    public String fileName;
    public String url;
    public int    stageCount;
    
    public Match (String fileName, String url, int stageCount) {

        this.fileName   = fileName;
        this.url        = url;
        this.stageCount = stageCount;

    }
}
