package Handlers;

import Events.ProgressEvt;

public interface ProgressHandler {

    abstract void handleProgressEvt(ProgressEvt evt);
}
