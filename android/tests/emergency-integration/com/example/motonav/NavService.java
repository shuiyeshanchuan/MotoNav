package com.example.motonav;
import android.content.Context;
public final class NavService {public static boolean failStarts;public static final Companion Companion=new Companion();public static final class Companion {public void start(Context context){if(failStarts)throw new IllegalStateException("simulated foreground-service rejection");} } }
