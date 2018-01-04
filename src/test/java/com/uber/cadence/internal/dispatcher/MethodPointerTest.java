/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package com.uber.cadence.internal.dispatcher;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Function;

public class MethodPointerTest {

//    public interface BiFunction<T, U, R> {
//
//        /**
//         * Applies this function to the given arguments.
//         *
//         * @param t the first function argument
//         * @param u the second function argument
//         * @return the function result
//         */
//        R apply(T t, U u);
//    }

    interface Foo {
//        @Activity(name="myActivityName")
        boolean bar(int i);

        int baz(float a, String b);
    }

//    public <T, R> Future<R> executeAsync(String activityName, T arg) {
//    }

    public <T, R> Future<R> executeAsync(Function<T, R> activity, T arg) {
        R result = activity.apply(arg);
        CompletableFuture<R> r = new CompletableFuture<>();
        r.complete(result);
        return r;
    }

    public <T, U, R> Future<R> executeAsync(BiFunction<T, U, R> activity, T arg1, U arg2) {
        System.out.println("executeAsync activity=" + activity);
        R result = activity.apply(arg1, arg2);
        CompletableFuture<R> r = new CompletableFuture<>();
        r.complete(result);
        return r;
    }


    public static Foo newFoo() {
        return new Foo() {
            @Override
            public boolean bar(int i) {
                System.out.println("bar invoked with " + i);
                return false;
            }

            @Override
            public int baz(float a, String b) {
                System.out.println("baz invoked with " + a + ", " + b);

                return 10;
            }
        };
    }

    @Test
    public void test() {
        Foo foo = newFoo();
        foo.bar(2);
        Future f = executeAsync(foo::bar, 6);
        System.out.println(executeAsync(foo::bar, 5));
        System.out.println(executeAsync(foo::baz, 2f, "bar"));

    }
}
