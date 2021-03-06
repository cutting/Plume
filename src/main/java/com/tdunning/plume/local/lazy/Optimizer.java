/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tdunning.plume.local.lazy;

import java.util.ArrayList;
import java.util.List;

import com.tdunning.plume.DoFn;
import com.tdunning.plume.EmitFn;
import com.tdunning.plume.PCollection;
import com.tdunning.plume.local.lazy.op.DeferredOp;
import com.tdunning.plume.local.lazy.op.Flatten;
import com.tdunning.plume.local.lazy.op.MultipleParallelDo;
import com.tdunning.plume.local.lazy.op.OneToOneOp;
import com.tdunning.plume.local.lazy.op.ParallelDo;

public class Optimizer {

  // Work-in-progress
  public <T> LazyCollection<T> optimize(LazyCollection<T> output) {
    fuseParallelDos(output);
    fuseSiblingParallelDos(output);
    return output;
  }
  
  /**
   * Here we will join ParallelDos that use the same PCollection into multiple-output {@link MultipleParallelDo}
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public <T> void fuseSiblingParallelDos(PCollection<T> arg) {
    LazyCollection<T> output = (LazyCollection<T>)arg;
    if(output.isMaterialized()) { // stop condition for recursive algorithm
      return;
    }
    DeferredOp dOp = output.getDeferredOp();
    if(!(dOp instanceof ParallelDo)) { // not a ParallelDo
      if(dOp instanceof OneToOneOp) {
        // Recursively apply this function to parent
        fuseSiblingParallelDos(((OneToOneOp)dOp).getOrigin());
        return;
      } 
      if(dOp instanceof Flatten) {
        Flatten<T> flatten = (Flatten)dOp;
        // Recursively apply this function to all parents
        for(PCollection<T> col: flatten.getOrigins()) {
          fuseSiblingParallelDos(col);
        }
        return;
      }
    }
    ParallelDo pDo = (ParallelDo)output.getDeferredOp();
    LazyCollection<T> orig = (LazyCollection<T>)pDo.getOrigin();
    int willAdd = 0;
    for(DeferredOp op: orig.getDownOps()) {
      if(op instanceof ParallelDo) {
        willAdd++;
      }
    }
    if(willAdd == 1) { // Parent doesn't have more ParallelDos to fuse
      // Recursively apply this function to parent
      fuseSiblingParallelDos(orig);
      return;
    }
    // MultipleParallelDo is viable, create it
    MultipleParallelDo<T> mPDo = new MultipleParallelDo<T>(orig);
    mPDo.addDest(pDo.getFunction(), output);
    orig.downOps.remove(pDo);
    output.deferredOp = mPDo;
    List<DeferredOp> newList = new ArrayList<DeferredOp>();
    for(DeferredOp op: orig.getDownOps()) {
      if(op instanceof ParallelDo) {
        ParallelDo thisPDo = (ParallelDo)op;
        mPDo.addDest(thisPDo.getFunction(), thisPDo.getDest());
        LazyCollection thisDest = (LazyCollection)thisPDo.getDest();
        thisDest.deferredOp = mPDo;
      } else {
        newList.add(op);
      }
    }
    newList.add(mPDo);
    orig.downOps = newList;
    // Recursively apply this function to parent
    fuseSiblingParallelDos(orig);
  }
  
  /**
   * We want to convert : {Orig2 => p2 => Orig1 => p1 => Output} to {Orig2 => p1(p2) => Output}
   * 
   * @param <T>
   * @param output
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public <T> void fuseParallelDos(PCollection<T> arg) {
    LazyCollection<T> output = (LazyCollection<T>)arg;
    if(output.isMaterialized()) { // stop condition for recursive algorithm
      return;
    }
    DeferredOp dOp = output.getDeferredOp();
    if(!(dOp instanceof ParallelDo)) { // not a ParallelDo
      if(dOp instanceof OneToOneOp) {
        // Recursively apply this function to parent
        fuseParallelDos(((OneToOneOp)dOp).getOrigin());
        return;
      } 
      if(dOp instanceof Flatten) {
        Flatten<T> flatten = (Flatten)dOp;
        // Recursively apply this function to all parents
        for(PCollection<T> col: flatten.getOrigins()) {
          fuseParallelDos(col);
        }
        return;
      }
    }
    ParallelDo p1 = (ParallelDo)output.getDeferredOp();
    LazyCollection orig1 = (LazyCollection)p1.getOrigin();
    if(orig1.isMaterialized()) {
      return;
    }
    if(!(orig1.getDeferredOp() instanceof ParallelDo)) {
      // Recursively apply this function to parent node
      fuseParallelDos(orig1);
    }
    // At this point we know ParallelDo fusion can be done -> Perform it
    ParallelDo p2 = (ParallelDo)orig1.getDeferredOp();
    final DoFn f1 = p1.getFunction();
    final DoFn f2 = p2.getFunction();
    // Define the joined function
    DoFn newFn = new DoFn() {
      @Override
      public void process(Object v, final EmitFn emitter) {
        f2.process(v, new EmitFn() {
          @Override
          public void emit(Object v) {
            f1.process(v, emitter);
          }
        });
      }
    };
    LazyCollection orig2 = (LazyCollection)p2.getOrigin();
    ParallelDo newPDo = new ParallelDo(newFn, orig2, orig1);
    // Clean & change pointers
    orig2.addDownOp(newPDo);
    orig1.downOps.remove(p1);
    output.deferredOp = newPDo;
    // Recursively apply this function to the same node => TODO Beware infinite recursion, properly test
    fuseParallelDos(output);
  }
}
