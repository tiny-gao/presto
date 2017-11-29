/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import io.airlift.concurrent.AsyncSemaphore;
import org.weakref.jmx.Managed;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.execution.SqlQueryManager.addCompletionCallback;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class QueryQueue
{
    private final AtomicInteger queryQueueSize = new AtomicInteger();
    private final AtomicInteger queuePermits;
    private final AsyncSemaphore<QueueEntry> asyncSemaphore;

    QueryQueue(Executor queryExecutor, int maxQueuedQueries, int maxConcurrentQueries)
    {
        requireNonNull(queryExecutor, "queryExecutor is null");
        checkArgument(maxQueuedQueries > 0, "maxQueuedQueries must be greater than zero");
        checkArgument(maxConcurrentQueries > 0, "maxConcurrentQueries must be greater than zero");

        int permits = maxQueuedQueries + maxConcurrentQueries;
        // Check for overflow
        checkArgument(permits > 0, "maxQueuedQueries + maxConcurrentQueries must be less than or equal to %s", Integer.MAX_VALUE);

        this.queuePermits = new AtomicInteger(permits);
        //异步信号量，传递一个执行器，来从队列中出队任务执行它。类似于自己实现了一套线程池，内部维持一个队列，用信号量来做同步
        //只要有人往里面提交任务都可以进行执行，QueuedExecution 和onDequeue任务
        //QueuedExecution包含以下实例
        /*private final QueryExecution queryExecution;  为具体的查询执行器比如：SqlQueryExecution，我们可以直接看他的start方法
        private final List<QueryQueue> nextQueues;
        private final ListenableFuture<?> listenableFuture;
        private final Executor executor;*/

        this.asyncSemaphore = new AsyncSemaphore<>(maxConcurrentQueries,
                //ExecutorService类型的执行器
                queryExecutor,
                //函数方法，输入是任务，输出是任务的future句柄
                //提交的是QueueEntry类型
                queueEntry -> {
                    //出队获取任务
                    QueuedExecution queuedExecution = queueEntry.dequeue();
                    if (queuedExecution != null) {
                        //执行任务，队列执行器会使用queryExecutor来执行任务
                        queuedExecution.start();
                        return queuedExecution.getCompletionFuture();
                    }
                    return Futures.immediateFuture(null);
                });
    }

    @Managed
    public int getQueueSize()
    {
        return queryQueueSize.get();
    }

    public boolean reserve(QueryExecution queryExecution)
    {
        if (queuePermits.decrementAndGet() < 0) {
            queuePermits.incrementAndGet();
            return false;
        }

        addCompletionCallback(queryExecution, queuePermits::incrementAndGet);
        return true;
    }

    public void enqueue(QueuedExecution queuedExecution)
    {
        queryQueueSize.incrementAndGet();

        // Add a callback to dequeue the entry if it is ever completed.
        // This enables us to remove the entry sooner if is cancelled before starting,
        // and has no effect if called after starting.
        QueueEntry entry = new QueueEntry(queuedExecution, queryQueueSize::decrementAndGet);
        queuedExecution.getCompletionFuture().addListener(entry::dequeue, MoreExecutors.directExecutor());

        //往异步信号量提交任务，异步信号量内部会不断迭代运行
        asyncSemaphore.submit(entry);
    }

    private static class QueueEntry
    {
        private final AtomicReference<QueuedExecution> queryExecution;
        private final Runnable onDequeue;

        private QueueEntry(QueuedExecution queuedExecution, Runnable onDequeue)
        {
            requireNonNull(queuedExecution, "queuedExecution is null");
            this.queryExecution = new AtomicReference<>(queuedExecution);
            this.onDequeue = requireNonNull(onDequeue, "onDequeue is null");
        }

        public QueuedExecution dequeue()
        {
            QueuedExecution value = queryExecution.getAndSet(null);
            if (value != null) {
                onDequeue.run();
            }
            return value;
        }
    }
}
