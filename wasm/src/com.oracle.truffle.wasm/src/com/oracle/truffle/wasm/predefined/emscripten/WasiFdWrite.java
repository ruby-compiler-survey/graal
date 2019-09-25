/*
 * Copyright (c) 2019, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.wasm.predefined.emscripten;

import java.util.function.Consumer;

import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.wasm.binary.WasmCodeEntry;
import com.oracle.truffle.wasm.binary.WasmContext;
import com.oracle.truffle.wasm.binary.WasmLanguage;
import com.oracle.truffle.wasm.binary.exception.WasmTrap;
import com.oracle.truffle.wasm.binary.memory.WasmMemory;
import com.oracle.truffle.wasm.predefined.WasmPredefinedRootNode;

public class WasiFdWrite extends WasmPredefinedRootNode {
    private TruffleLogger logger = TruffleLogger.getLogger("wasm");

    public WasiFdWrite(WasmLanguage language, WasmMemory memory) {
        super(language, null, memory);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        assert args.length == 4;
        for (Object arg : args) {
            logger.finest(() -> "argument: " + arg);
        }

        int stream = (int) args[0];
        int iov = (int) args[1];
        int iovcnt = (int) args[2];
        int pnum = (int) args[3];

        Consumer<Character> charPrinter;
        switch (stream) {
            case 1:
                charPrinter = System.out::print;
                break;
            case 2:
                charPrinter = System.err::print;
                break;
            default:
                throw new WasmTrap(this, "WasiFdWrite: invalid file stream");
        }

        logger.finest("WasiFdWrite EXECUTE");

        int num = 0;
        for (int i = 0; i < iovcnt; i++) {
            int ptr = memory.load_i32(iov + i * 8);
            int len = memory.load_i32(iov + (i * 8 + 4));
            for (int j = 0; j < len; j++) {
                charPrinter.accept((char) memory.load_i32_8u(ptr + j));
            }
            num += len;
            memory.store_i32(pnum, num);
        }

        return 0;
    }

    @Override
    public String name() {
        return "___wasi_fd_write";
    }
}
