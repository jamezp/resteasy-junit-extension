/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.resteasy.junit.extension.extensions;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Predicate;

import jakarta.inject.Inject;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

import dev.resteasy.junit.extension.api.InjectionProducer;

/**
 * An extension for using {@linkplain InjectionProducer producers} to inject fields annotated with {@link Inject} and
 * method or constructor parameters.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class InjectionProducerExtension
        implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback, ParameterResolver {

    private final BlockingDeque<AutoCloseable> resources = new LinkedBlockingDeque<>();
    private final ServiceLoader<InjectionProducer> producers;

    public InjectionProducerExtension() {
        producers = ServiceLoader.load(InjectionProducer.class);
    }

    @Override
    public void beforeAll(final ExtensionContext context) {
        injectStaticFields(context, context.getRequiredTestClass());
    }

    @Override
    public void beforeEach(final ExtensionContext context) {
        context.getRequiredTestInstances().getAllInstances()
                .forEach(instance -> injectInstanceFields(context, instance));
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        for (InjectionProducer producer : producers) {
            if (producer.canInject(extensionContext, parameterContext.getParameter().getType(), parameterContext.getParameter()
                    .getAnnotations())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        // Find the producer which can provide this parameter
        InjectionProducer injectionProducer = null;
        for (InjectionProducer producer : producers) {
            if (producer.canInject(extensionContext, parameterContext.getParameter().getType(), parameterContext.getParameter()
                    .getAnnotations())) {
                injectionProducer = producer;
                break;
            }
        }
        if (injectionProducer == null) {
            return null;
        }
        try {
            final Object value = injectionProducer.produce(extensionContext, parameterContext.getParameter()
                    .getType(), parameterContext.getParameter().getAnnotations());
            if (value instanceof AutoCloseable) {
                resources.add((AutoCloseable) value);
            }
            return value;
        } catch (Throwable e) {
            throw new ParameterResolutionException(
                    String.format("Failed to resolve parameter '%s'.", parameterContext.getParameter()), e);
        }
    }

    @Override
    public void afterAll(final ExtensionContext context) {
        AutoCloseable closeable;
        while ((closeable = resources.pollFirst()) != null) {
            try {
                closeable.close();
            } catch (Throwable t) {
                // TODO (jrp) should we capture these?
            }
        }
    }

    private void injectStaticFields(final ExtensionContext context, final Class<?> testClass) {
        injectFields(context, null, testClass, (f) -> Modifier.isStatic(f.getModifiers()));
    }

    private void injectInstanceFields(final ExtensionContext context, final Object instance) {
        injectFields(context, instance, instance.getClass(), (f) -> !Modifier.isStatic(f.getModifiers()));
    }

    private void injectFields(final ExtensionContext context, final Object testInstance, final Class<?> testClass,
            final Predicate<Field> predicate) {

        AnnotationSupport.findAnnotatedFields(testClass, Inject.class, predicate).forEach(field -> {
            if (Modifier.isFinal(field.getModifiers())) {
                throw new ExtensionConfigurationException(
                        String.format("Field '%s' cannot be final for injecting a REST client.", field));
            }
            // Find the producer which can provide this parameter
            InjectionProducer injectionProducer = null;
            for (InjectionProducer producer : producers) {
                if (producer.canInject(context, field.getType(), field.getAnnotations())) {
                    injectionProducer = producer;
                    break;
                }
            }
            if (injectionProducer == null) {
                throw new ExtensionConfigurationException(
                        String.format("Could not find InjectionProducer for field '%s' of type %s.", field, field.getType()
                                .getName()));
            }
            try {
                final Object value = injectionProducer.produce(context, field.getType(), field.getAnnotations());
                if (value instanceof AutoCloseable) {
                    resources.add((AutoCloseable) value);
                }
                if (field.trySetAccessible()) {
                    field.set(testInstance, value);
                } else {
                    throw new ParameterResolutionException(
                            String.format("Could not make field %s accessible for injection.", field));
                }
            } catch (Throwable e) {
                if (e instanceof ParameterResolutionException) {
                    throw (ParameterResolutionException) e;
                }
                throw new ParameterResolutionException(
                        String.format("Could not make field %s accessible for injection.", field), e);
            }
        });
    }
}
