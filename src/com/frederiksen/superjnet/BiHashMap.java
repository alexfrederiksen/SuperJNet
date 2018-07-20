package com.frederiksen.superjnet;

import java.util.HashMap;
import java.util.Set;

// A -> [Hash Function] -> B
// B -> [Hash Function] -> A

/**
 * Hash map that is one-to-one using two {@link HashMap} instances,
 * thus can go forward and backwards.
 *
 * @param <A> left value
 * @param <B> right value
 */
public class BiHashMap<A, B> {
    private HashMap<A, B> forward = new HashMap<>();
    private HashMap<B, A> backward = new HashMap<>();

    public void put(A a, B b) {
        forward.put(a, b);
        backward.put(b, a);
    }

    public void remove(A a, B b) {
        forward.remove(a, b);
        backward.remove(b, a);
    }

    public void removeA(A a) {
        backward.remove(forward.get(a));
        forward.remove(a);
    }

    public void removeB(B b) {
        forward.remove((backward.get(b)));
        backward.remove(b);
    }

    public void clear() {
        forward.clear();
        backward.clear();
    }
    public B getB(A a) {
        return forward.get(a);
    }

    public A getA(B b) {
        return backward.get(b);
    }

    public Set<A> getA() {
        return forward.keySet();
    }

    public Set<B> getB() {
        return backward.keySet();
    }

    public boolean containsA(A a) {
        return forward.containsKey(a);
    }

    public boolean containsB(B b) {
        return backward.containsKey(b);
    }
}
