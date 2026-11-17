package com.example.e2e.box;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class BoxStore {
    private final Map<String, Box> boxes = new ConcurrentHashMap<>();

    public void save(Box box) {
        boxes.put(box.id(), box);
    }

    public void clear(String boxId) {
        boxes.remove(boxId);
    }

    public BoxKeeper keeper(String boxId) {
        return new BoxKeeper(boxId);
    }

    public boolean pay(String boxId) {
        return boxes.containsKey(boxId);
    }
}
