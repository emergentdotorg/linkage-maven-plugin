package org.emergent.maven.linkage.fixtures;

import java.io.IOException;
import java.util.Objects;

@Marker(Integer.class)
public class Concrete extends AbstractBase {

    @Override
    public String name() {
        return "concrete";
    }

    @Override
    public void run() throws IOException {
        try {
            Runnable runnable = () -> System.out.println(greet());
            runnable.run();
        } catch (RuntimeException e) {
            throw new IOException(e);
        }
        //noinspection OnlyOneElementUsed
        String[] copy = new String[][] {{"x"}, {"y"}}[0].clone();
        Objects.requireNonNull(copy);
        Class<?> literal = String.class;
        Objects.requireNonNull(literal);
    }
}
