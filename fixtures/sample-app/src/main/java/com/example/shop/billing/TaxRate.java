package com.example.shop.billing;

/** #58 f: near miss, read through a repository that cannot write it: no change to audit. */
public record TaxRate(String country, int basisPoints) { // expect-not: DATA_CHANGES_UNAUDITED
}
