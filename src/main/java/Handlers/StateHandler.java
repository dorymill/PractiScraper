package Handlers;

import Events.StateEvt;

public interface StateHandler {

    abstract void handleStateEvt(StateEvt evt);
}
