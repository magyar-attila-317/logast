package com.example.e2e.shelf;

import com.example.e2e.gen.BinApi;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BinController implements BinApi {
    @Override
    public String empty(String binId) {
        return "emptied";
    }
}
