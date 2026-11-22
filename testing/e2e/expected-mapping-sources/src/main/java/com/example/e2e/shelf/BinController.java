package com.example.e2e.shelf;

import com.example.e2e.gen.BinApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class BinController implements BinApi {
    @Override
    public String empty(String binId) {
        log.info("Bin deletion started"); // logast:wording
        return "emptied";
    }
}
