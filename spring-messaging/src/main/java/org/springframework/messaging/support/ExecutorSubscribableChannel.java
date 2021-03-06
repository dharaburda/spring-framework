/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.messaging.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.SubscribableChannel;

/**
 * A {@link SubscribableChannel} that sends messages to each of its subscribers.
 *
 * @author Phillip Webb
 * @author Rossen Stoyanchev
 * @since 4.0
 */
public class ExecutorSubscribableChannel extends AbstractSubscribableChannel {

	private final Executor executor;

	private final List<ExecutorChannelInterceptor> executorInterceptors = new ArrayList<ExecutorChannelInterceptor>(4);


	/**
	 * Create a new {@link ExecutorSubscribableChannel} instance where messages will be sent
	 * in the callers thread.
	 */
	public ExecutorSubscribableChannel() {
		this(null);
	}

	/**
	 * Create a new {@link ExecutorSubscribableChannel} instance where messages will be sent
	 * via the specified executor.
	 * @param executor the executor used to send the message or {@code null} to execute in
	 *        the callers thread.
	 */
	public ExecutorSubscribableChannel(Executor executor) {
		this.executor = executor;
	}


	public Executor getExecutor() {
		return this.executor;
	}

	@Override
	public void setInterceptors(List<ChannelInterceptor> interceptors) {
		super.setInterceptors(interceptors);
		this.executorInterceptors.clear();
		for (ChannelInterceptor interceptor : interceptors) {
			if (interceptor instanceof ExecutorChannelInterceptor) {
				this.executorInterceptors.add((ExecutorChannelInterceptor) interceptor);
			}
		}
	}

	@Override
	public void addInterceptor(ChannelInterceptor interceptor) {
		super.addInterceptor(interceptor);
		if (interceptor instanceof ExecutorChannelInterceptor) {
			this.executorInterceptors.add((ExecutorChannelInterceptor) interceptor);
		}
	}


	@Override
	public boolean sendInternal(final Message<?> message, long timeout) {
		for (MessageHandler subscriber : getSubscribers()) {
			ExecutorChannelInterceptorChain chain = new ExecutorChannelInterceptorChain();
			SendTask sendTask = new SendTask(message, this, subscriber, chain);
			if (this.executor == null) {
				sendTask.run();
			}
			else {
				this.executor.execute(sendTask);
			}
		}
		return true;
	}


	/**
	 * Helps with the invocation of the target MessageHandler and interceptors.
	 */
	private static class SendTask implements Runnable {

		private final Message<?> inputMessage;

		private final MessageChannel channel;

		private final MessageHandler handler;

		private final ExecutorChannelInterceptorChain chain;


		public SendTask(Message<?> message, MessageChannel channel, MessageHandler handler,
				ExecutorChannelInterceptorChain chain) {

			this.inputMessage = message;
			this.channel = channel;
			this.handler = handler;
			this.chain = chain;
		}

		@Override
		public void run() {
			Message<?> message = this.inputMessage;
			try {
				message = chain.applyBeforeHandle(message, this.channel, this.handler);
				if (message == null) {
					return;
				}
				this.handler.handleMessage(message);
				this.chain.triggerAfterMessageHandled(message, this.channel, this.handler, null);
			}
			catch (Exception ex) {
				this.chain.triggerAfterMessageHandled(message, this.channel, this.handler, ex);
				if (ex instanceof MessagingException) {
					throw (MessagingException) ex;
				}
				throw new MessageDeliveryException(message,
						"Failed to handle message to " + this.channel + " in " + this.handler, ex);
			}
			catch (Error ex) {
				this.chain.triggerAfterMessageHandled(message, this.channel, this.handler,
						new MessageDeliveryException(message,
								"Failed to handle message to " + this.channel + " in " + this.handler, ex));
				throw ex;
			}
		}
	}

	/**
	 * Helps with the invocation of configured executor channel interceptors.
	 */
	private class ExecutorChannelInterceptorChain {

		private int interceptorIndex = -1;


		public Message<?> applyBeforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
			for (ExecutorChannelInterceptor interceptor : executorInterceptors) {
				message = interceptor.beforeHandle(message, channel, handler);
				if (message == null) {
					String name = interceptor.getClass().getSimpleName();
					if (logger.isDebugEnabled()) {
						logger.debug(name + " returned null from beforeHandle, i.e. precluding the send.");
					}
					triggerAfterMessageHandled(message, channel, handler, null);
					return null;
				}
				this.interceptorIndex++;
			}
			return message;
		}

		public void triggerAfterMessageHandled(Message<?> message, MessageChannel channel,
				MessageHandler handler, Exception ex) {

			for (int i = this.interceptorIndex; i >= 0; i--) {
				ExecutorChannelInterceptor interceptor = executorInterceptors.get(i);
				try {
					interceptor.afterMessageHandled(message, channel, handler, ex);
				}
				catch (Throwable ex2) {
					logger.error("Exception from afterMessageHandled in " + interceptor, ex2);
				}
			}
		}
	}

}
