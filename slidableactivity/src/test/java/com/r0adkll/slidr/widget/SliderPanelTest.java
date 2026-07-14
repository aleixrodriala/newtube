package com.r0adkll.slidr.widget;

import android.content.Context;
import android.view.MotionEvent;

import com.r0adkll.slidr.model.SlidrConfig;
import com.r0adkll.slidr.model.SlidrPosition;
import com.r0adkll.slidr.util.ViewDragHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * Created by farid on 18/01/16.
 */
@RunWith(MockitoJUnitRunner.class)
public class SliderPanelTest {

    @Mock
    Context context;

    @Mock
    MotionEvent motionEvent;

    @Test
    public void testOnInterceptTouchEvent_whenLocked() throws Exception {
        //given
        SliderPanel sliderPanel = new SliderPanel(context);
        setInternalState(sliderPanel, "isLocked", true);

        //when
        boolean result = sliderPanel.onInterceptTouchEvent(motionEvent);

        //then
        assertFalse("Result must be false when isLocked is true", result);
    }


    @Test
    public void testOnInterceptTouchEvent_whenNotLoacked_edgeOnly() throws Exception {
        //given
        SliderPanel sliderPanel = Mockito.spy(new SliderPanel(context));
        when(sliderPanel.getWidth()).thenReturn(10);

        SlidrConfig slidrConfig = Mockito.mock(SlidrConfig.class);
        when(slidrConfig.isEdgeOnly()).thenReturn(true);
        when(slidrConfig.getPosition()).thenReturn(SlidrPosition.LEFT);
        setInternalState(sliderPanel, "isLocked", false);
        setInternalState(sliderPanel, "config", slidrConfig);

        //when
        boolean result = sliderPanel.onInterceptTouchEvent(motionEvent);

        //then
        assertFalse("Result must be false", result);
    }

    @Test
    public void testOnTouchEvent_whenLocked() throws Exception {
        //given
        SliderPanel sliderPanel = Mockito.spy(new SliderPanel(context));
        setInternalState(sliderPanel, "isLocked", true);

        //when
        boolean result = sliderPanel.onTouchEvent(motionEvent);

        //then
        assertFalse("Result must be false when locked", result);
    }

    @Test
    public void testOnTouchEvent_whenNotLocked() throws Exception {
        //given
        SliderPanel sliderPanel = Mockito.spy(new SliderPanel(context));
        setInternalState(sliderPanel, "isLocked", false);

        ViewDragHelper viewDragHelper = Mockito.mock(ViewDragHelper.class);
        setInternalState(sliderPanel, "dragHelper", viewDragHelper);

        //when
        boolean result = sliderPanel.onTouchEvent(motionEvent);

        //then
        assertTrue("Result must be true when not locked", result);
    }

    @Test
    public void testOnTouchEvent_whenNotLocked_butExceptionInProcessTouchEvent() throws Exception {
        //given
        SliderPanel sliderPanel = Mockito.spy(new SliderPanel(context));
        setInternalState(sliderPanel, "isLocked", false);

        ViewDragHelper viewDragHelper = Mockito.mock(ViewDragHelper.class);
        doThrow(new IllegalArgumentException()).when(viewDragHelper).processTouchEvent(motionEvent);
        setInternalState(sliderPanel, "dragHelper", viewDragHelper);

        //when
        boolean result = sliderPanel.onTouchEvent(motionEvent);

        //then
        assertFalse("Result must be false when not locked but exception occured during processTouchEvent()", result);
    }

    private static void setInternalState(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
