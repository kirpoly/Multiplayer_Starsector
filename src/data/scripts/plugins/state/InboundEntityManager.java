package data.scripts.plugins.state;

import data.scripts.net.data.BasePackable;

import java.util.Map;

public interface InboundEntityManager {
    void processDeltas(Map<Integer, BasePackable> toProcess);

    void updateEntities();
}
