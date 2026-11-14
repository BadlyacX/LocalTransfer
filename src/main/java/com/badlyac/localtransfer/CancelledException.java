package com.badlyac.localtransfer;

import java.io.IOException;

public class CancelledException extends IOException {
    public CancelledException() {
        super("已取消");
    }
}
