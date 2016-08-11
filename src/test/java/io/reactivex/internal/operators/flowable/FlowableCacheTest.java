/**
 * Copyright 2016 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.operators.flowable;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.*;
import org.reactivestreams.*;

import io.reactivex.Flowable;
import io.reactivex.exceptions.TestException;
import io.reactivex.functions.Consumer;
import io.reactivex.internal.subscriptions.BooleanSubscription;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subscribers.TestSubscriber;

public class FlowableCacheTest {
    @Test
    public void testColdReplayNoBackpressure() {
        FlowableCache<Integer> source = FlowableCache.from(Flowable.range(0, 1000));
        
        assertFalse("Source is connected!", source.isConnected());
        
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
        
        source.subscribe(ts);

        assertTrue("Source is not connected!", source.isConnected());
        assertFalse("Subscribers retained!", source.hasObservers());
        
        ts.assertNoErrors();
        ts.assertTerminated();
        List<Integer> onNextEvents = ts.values();
        assertEquals(1000, onNextEvents.size());

        for (int i = 0; i < 1000; i++) {
            assertEquals((Integer)i, onNextEvents.get(i));
        }
    }
    @Test
    public void testColdReplayBackpressure() {
        FlowableCache<Integer> source = FlowableCache.from(Flowable.range(0, 1000));
        
        assertFalse("Source is connected!", source.isConnected());
        
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>(0L);
        ts.request(10);
        
        source.subscribe(ts);

        assertTrue("Source is not connected!", source.isConnected());
        assertTrue("Subscribers not retained!", source.hasObservers());
        
        ts.assertNoErrors();
        ts.assertNotComplete();
        List<Integer> onNextEvents = ts.values();
        assertEquals(10, onNextEvents.size());

        for (int i = 0; i < 10; i++) {
            assertEquals((Integer)i, onNextEvents.get(i));
        }
        
        ts.dispose();
        assertFalse("Subscribers retained!", source.hasObservers());
    }
    
    @Test
    public void testCache() throws InterruptedException {
        final AtomicInteger counter = new AtomicInteger();
        Flowable<String> o = Flowable.unsafeCreate(new Publisher<String>() {

            @Override
            public void subscribe(final Subscriber<? super String> observer) {
                observer.onSubscribe(new BooleanSubscription());
                new Thread(new Runnable() {

                    @Override
                    public void run() {
                        counter.incrementAndGet();
                        System.out.println("published observable being executed");
                        observer.onNext("one");
                        observer.onComplete();
                    }
                }).start();
            }
        }).cache();

        // we then expect the following 2 subscriptions to get that same value
        final CountDownLatch latch = new CountDownLatch(2);

        // subscribe once
        o.subscribe(new Consumer<String>() {
            @Override
            public void accept(String v) {
                    assertEquals("one", v);
                    System.out.println("v: " + v);
                    latch.countDown();
            }
        });

        // subscribe again
        o.subscribe(new Consumer<String>() {
            @Override
            public void accept(String v) {
                    assertEquals("one", v);
                    System.out.println("v: " + v);
                    latch.countDown();
            }
        });

        if (!latch.await(1000, TimeUnit.MILLISECONDS)) {
            fail("subscriptions did not receive values");
        }
        assertEquals(1, counter.get());
    }

    @Test
    public void testUnsubscribeSource() {
        Runnable unsubscribe = mock(Runnable.class);
        Flowable<Integer> o = Flowable.just(1).doOnCancel(unsubscribe).cache();
        o.subscribe();
        o.subscribe();
        o.subscribe();
        verify(unsubscribe, times(1)).run();
    }
    
    @Test
    public void testTake() {
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();

        FlowableCache<Integer> cached = FlowableCache.from(Flowable.range(1, 100));
        cached.take(10).subscribe(ts);
        
        ts.assertNoErrors();
        ts.assertComplete();
        ts.assertValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
//        ts.assertUnsubscribed(); // FIXME no longer valid 
        assertFalse(cached.hasObservers());
    }
    
    @Test
    public void testAsync() {
        Flowable<Integer> source = Flowable.range(1, 10000);
        for (int i = 0; i < 100; i++) {
            TestSubscriber<Integer> ts1 = new TestSubscriber<Integer>();
            
            FlowableCache<Integer> cached = FlowableCache.from(source);
            
            cached.observeOn(Schedulers.computation()).subscribe(ts1);
            
            ts1.awaitTerminalEvent(2, TimeUnit.SECONDS);
            ts1.assertNoErrors();
            ts1.assertComplete();
            assertEquals(10000, ts1.values().size());
            
            TestSubscriber<Integer> ts2 = new TestSubscriber<Integer>();
            cached.observeOn(Schedulers.computation()).subscribe(ts2);
            
            ts2.awaitTerminalEvent(2, TimeUnit.SECONDS);
            ts2.assertNoErrors();
            ts2.assertComplete();
            assertEquals(10000, ts2.values().size());
        }
    }
    @Test
    public void testAsyncComeAndGo() {
        Flowable<Long> source = Flowable.interval(1, 1, TimeUnit.MILLISECONDS)
                .take(1000)
                .subscribeOn(Schedulers.io());
        FlowableCache<Long> cached = FlowableCache.from(source);
        
        Flowable<Long> output = cached.observeOn(Schedulers.computation());
        
        List<TestSubscriber<Long>> list = new ArrayList<TestSubscriber<Long>>(100);
        for (int i = 0; i < 100; i++) {
            TestSubscriber<Long> ts = new TestSubscriber<Long>();
            list.add(ts);
            output.skip(i * 10).take(10).subscribe(ts);
        }

        List<Long> expected = new ArrayList<Long>();
        for (int i = 0; i < 10; i++) {
            expected.add((long)(i - 10));
        }
        int j = 0;
        for (TestSubscriber<Long> ts : list) {
            ts.awaitTerminalEvent(3, TimeUnit.SECONDS);
            ts.assertNoErrors();
            ts.assertComplete();
            
            for (int i = j * 10; i < j * 10 + 10; i++) {
                expected.set(i - j * 10, (long)i);
            }
            
            ts.assertValueSequence(expected);
            
            j++;
        }
    }
    
    @Test
    public void testNoMissingBackpressureException() {
        final int m = 4 * 1000 * 1000;
        Flowable<Integer> firehose = Flowable.unsafeCreate(new Publisher<Integer>() {
            @Override
            public void subscribe(Subscriber<? super Integer> t) {
                t.onSubscribe(new BooleanSubscription());
                for (int i = 0; i < m; i++) {
                    t.onNext(i);
                }
                t.onComplete();
            }
        });
        
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
        firehose.cache().observeOn(Schedulers.computation()).takeLast(100).subscribe(ts);
        
        ts.awaitTerminalEvent(3, TimeUnit.SECONDS);
        ts.assertNoErrors();
        ts.assertComplete();
        
        assertEquals(100, ts.values().size());
    }
    
    @Test
    public void testValuesAndThenError() {
        Flowable<Integer> source = Flowable.range(1, 10)
                .concatWith(Flowable.<Integer>error(new TestException()))
                .cache();
        
        
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
        source.subscribe(ts);
        
        ts.assertValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        ts.assertNotComplete();
        ts.assertError(TestException.class);
        
        TestSubscriber<Integer> ts2 = new TestSubscriber<Integer>();
        source.subscribe(ts2);
        
        ts2.assertValues(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        ts2.assertNotComplete();
        ts2.assertError(TestException.class);
    }
    
    @Test
    public void unsafeChildThrows() {
        final AtomicInteger count = new AtomicInteger();
        
        Flowable<Integer> source = Flowable.range(1, 100)
        .doOnNext(new Consumer<Integer>() {
            @Override
            public void accept(Integer t) {
                count.getAndIncrement();
            }
        })
        .cache();
        
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                throw new TestException();
            }
        };
        
        source.subscribe(ts);
        
        Assert.assertEquals(100, count.get());

        ts.assertNoValues();
        ts.assertNotComplete();
        ts.assertError(TestException.class);
    }
}