package org.springframework.cloud.sleuth.instrument.rxjava;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.TraceKeys;
import org.springframework.cloud.sleuth.Tracer;
import rx.functions.Action0;
import rx.plugins.RxJavaErrorHandler;
import rx.plugins.RxJavaObservableExecutionHook;
import rx.plugins.RxJavaPlugins;
import rx.plugins.RxJavaSchedulersHook;

/**
 * {@link RxJavaSchedulersHook} that wraps an {@link Action0} into its tracing
 * representation.
 *
 * @author Shivang Shah
 * @since 1.0.0
 */
class SleuthRxJavaSchedulersHook extends RxJavaSchedulersHook {

	private static final Log log = LogFactory.getLog(SleuthRxJavaSchedulersHook.class);

	private static final String RXJAVA_COMPONENT = "rxjava";
	private final Tracer tracer;
	private final TraceKeys traceKeys;
	private RxJavaSchedulersHook delegate;

	SleuthRxJavaSchedulersHook(Tracer tracer, TraceKeys traceKeys) {
		this.tracer = tracer;
		this.traceKeys = traceKeys;
		try {
			this.delegate = RxJavaPlugins.getInstance().getSchedulersHook();
			if (this.delegate instanceof SleuthRxJavaSchedulersHook) {
				return;
			}
			RxJavaErrorHandler errorHandler = RxJavaPlugins.getInstance().getErrorHandler();
			RxJavaObservableExecutionHook observableExecutionHook
				= RxJavaPlugins.getInstance().getObservableExecutionHook();
			logCurrentStateOfRxJavaPlugins(errorHandler, observableExecutionHook);
			RxJavaPlugins.getInstance().reset();
			RxJavaPlugins.getInstance().registerSchedulersHook(this);
			RxJavaPlugins.getInstance().registerErrorHandler(errorHandler);
			RxJavaPlugins.getInstance().registerObservableExecutionHook(observableExecutionHook);
		} catch (Exception e) {
			log.error("Failed to register Sleuth RxJava SchedulersHook", e);
		}
	}

	private void logCurrentStateOfRxJavaPlugins(RxJavaErrorHandler errorHandler,
		RxJavaObservableExecutionHook observableExecutionHook) {
		log.debug("Current RxJava plugins configuration is ["
			+ "schedulersHook [" + this.delegate + "],"
			+ "errorHandler [" + errorHandler + "],"
			+ "observableExecutionHook [" + observableExecutionHook + "],"
			+ "]");
		log.debug("Registering Sleuth RxJava Schedulers Hook.");
	}

	@Override
	public Action0 onSchedule(Action0 action) {
		if (action instanceof TraceAction) {
			return action;
		}
		Action0 wrappedAction = this.delegate != null
			? this.delegate.onSchedule(action) : action;
		if (wrappedAction instanceof TraceAction) {
			return action;
		}
		return super.onSchedule(new TraceAction(this.tracer, this.traceKeys, wrappedAction));
	}

	static class TraceAction implements Action0 {

		private final Action0 actual;
		private Tracer tracer;
		private TraceKeys traceKeys;
		private Span parent;

		public TraceAction(Tracer tracer, TraceKeys traceKeys, Action0 actual) {
			this.tracer = tracer;
			this.traceKeys = traceKeys;
			this.parent = tracer.getCurrentSpan();
			this.actual = actual;
		}

		@Override
		public void call() {
			Span span = this.parent;
			boolean created = false;
			if (span != null) {
				span = this.tracer.continueSpan(span);
			} else {
				span = this.tracer.createSpan(RXJAVA_COMPONENT);
				this.tracer.addTag(Span.SPAN_LOCAL_COMPONENT_TAG_NAME, RXJAVA_COMPONENT);
				this.tracer.addTag(this.traceKeys.getAsync().getPrefix()
					+ this.traceKeys.getAsync().getThreadNameKey(), Thread.currentThread().getName());
				created = true;
			}
			try {
				this.actual.call();
			} finally {
				if (created) {
					this.tracer.close(span);
				} else {
					this.tracer.detach(span);
				}
			}
		}
	}
}
