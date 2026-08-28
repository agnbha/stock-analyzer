package com.stockanalyzer.model;

/**
 * Intraday (MIS) and delivery (CNC) carry different charges and different tax
 * character, so they are never netted against each other.
 */
public enum Product { MIS, CNC }
