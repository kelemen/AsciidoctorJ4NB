package org.netbeans.asciidoc.highlighter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import javax.swing.text.StringContent;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.netbeans.junit.MockServices;
import org.netbeans.modules.csl.api.StructureItem;
import org.netbeans.modules.parsing.api.Snapshot;
import org.netbeans.modules.parsing.api.Source;
import org.netbeans.modules.parsing.implspi.EnvironmentFactory;
import org.netbeans.modules.parsing.implspi.SchedulerControl;
import org.netbeans.modules.parsing.implspi.SourceControl;
import org.netbeans.modules.parsing.implspi.SourceEnvironment;
import org.netbeans.modules.parsing.spi.Parser;
import org.netbeans.modules.parsing.spi.Scheduler;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

import static org.junit.Assert.*;

public class AsciidoctorStructureScannerTest {
    @BeforeClass
    public static void setupTests() {
        MockServices.setServices(TestEnv.class);
    }

    @AfterClass
    public static void destroyTests() {
        MockServices.setServices();
    }

    @Test
    public void testEmptyStructure() throws Exception {
        testStructure((tokens, expectations) -> {
        });
    }

    @Test
    public void testTextOnly() throws Exception {
        testStructure((tokens, expectations) -> {
            tokens.addToken(AsciidoctorTokenId.OTHER, "something bla bla");
        });
    }

    @Test
    public void testSingleTitle() throws Exception {
        testStructure((tokens, expectations) -> {
            AsciidoctorToken header = tokens.addToken(AsciidoctorTokenId.HEADER2, "== My Header");

            expectations.expectHeaderNode(header, "My Header", tokens.getInputSize() - 1);
        });
    }

    @Test
    public void testSimpleStructure() throws Exception {
        testStructure((tokens, expectations) -> {
            tokens.addToken(AsciidoctorTokenId.OTHER, "something\n\n");
            AsciidoctorToken header11 = tokens.addToken(AsciidoctorTokenId.HEADER2, "== First header 2");
            tokens.addToken(AsciidoctorTokenId.OTHER, "\n\nsection body line 1\nsection body line 2\n\n");
            AsciidoctorToken header12 = tokens.addToken(AsciidoctorTokenId.HEADER2, "== Second header 2");
            tokens.addToken(AsciidoctorTokenId.OTHER, "\n\nsection body line 3\n\n");
            AsciidoctorToken header21 = tokens.addToken(AsciidoctorTokenId.HEADER3, "=== Third header 3");
            tokens.addToken(AsciidoctorTokenId.OTHER, "\n\n");
            tokens.addToken(AsciidoctorTokenId.CODE_BLOCK, "----\nMy Test Code Block\n----");
            tokens.addToken(AsciidoctorTokenId.OTHER, "\n\nfinal part\n");

            long endPos = tokens.getInputSize() - 1;

            expectations.expectHeaderNode(header11, "First header 2", header12.getStartIndex() - 1);
            expectations.expectHeaderNode(header12, "Second header 2", endPos, (level2, level2Exp) -> {
                level2Exp.expectHeaderNode(header21, "Third header 3", endPos);
            });
        });
    }

    private void testStructure(TokenListSetup setup) throws Exception {
        TokenListBuilder tokensBuilder = new TokenListBuilder();
        StructureExpectations expectations = new StructureExpectations();

        setup.setupTest(tokensBuilder, expectations);

        AsciidoctorStructureScanner scanner = new AsciidoctorStructureScanner();

        List<? extends StructureItem> items = scanner.scan(tokensBuilder.getParserResult());
        expectations.verifyNodes(items);
    }

    public static final class TestEnv implements EnvironmentFactory {
        public TestEnv() {
        }

        @Override
        public Lookup getContextLookup() {
            return Lookup.EMPTY;
        }

        private RuntimeException unexpectedCall() {
            throw new AssertionError("Unexpected EnvironmentFactory access");
        }

        @Override
        public Class<? extends Scheduler> findStandardScheduler(String schedulerName) {
            throw unexpectedCall();
        }

        @Override
        public Parser findMimeParser(Lookup context, String mimeType) {
            throw unexpectedCall();
        }

        @Override
        public Collection<? extends Scheduler> getSchedulers(Lookup context) {
            return Collections.emptyList();
        }

        @Override
        public SourceEnvironment createEnvironment(Source src, SourceControl control) {
            return new SourceEnvironment(control) {
                @Override
                public Document readDocument(FileObject f, boolean forceOpen) throws IOException {
                    throw unexpectedCall();
                }

                @Override
                public void attachScheduler(SchedulerControl s, boolean attach) {
                    throw unexpectedCall();
                }

                @Override
                public void activate() {
                }

                @Override
                public boolean isReparseBlocked() {
                    return false;
                }
            };
        }

        @Override
        public <T> T runPriorityIO(Callable<T> r) throws Exception {
            throw unexpectedCall();
        }
    }

    private static final class TokenListBuilder {
        private final List<AsciidoctorToken> tokens;
        private final StringBuilder currentInput;
        private int offset;

        public TokenListBuilder() {
            this.offset = 0;
            this.tokens = new ArrayList<>();
            this.currentInput = new StringBuilder(128);
        }

        public AsciidoctorToken addToken(AsciidoctorTokenId id, String content) {
            AsciidoctorToken token = new AsciidoctorToken(id, offset, offset + content.length());
            currentInput.append(content);
            tokens.add(token);
            offset += token.getLength();

            return token;
        }

        public int getInputSize() {
            return currentInput.length();
        }

        public String getAllInput() {
            return currentInput.toString();
        }

        public Snapshot getInputSnapshot() throws Exception {
            return createSnapshot(getAllInput());
        }

        private Snapshot createSnapshot(String content) throws Exception {
            StringContent docContent = new StringContent();
            docContent.insertString(0, content);
            Document document = new PlainDocument(docContent);
            document.putProperty("mimeType", AsciidoctorLanguageConfig.MIME_TYPE);

            Source source = Source.create(document);
            return source.createSnapshot();
        }

        public AsciidoctorParserResult getParserResult() throws Exception {
            return new AsciidoctorParserResult(getInputSnapshot(), getCurrentTokens());
        }

        public List<AsciidoctorToken> getCurrentTokens() {
            return Collections.unmodifiableList(new ArrayList<>(tokens));
        }
    }

    private static final class StructureExpectations {
        private final List<NodeExpectation> nodeVerifiers;

        public StructureExpectations() {
            this.nodeVerifiers = new ArrayList<>();
        }

        public void expectNode(NodeVerifier verifier) {
            nodeVerifiers.add(new NodeExpectation(verifier));
        }

        public void expectHeaderNode(AsciidoctorToken token, String content, long endPos) {
            expectHeaderNode(token, content, endPos, NodeVerifier.NO_OP);
        }

        public void expectHeaderNode(AsciidoctorToken token, String content, long endPos, NodeVerifier verifier) {
            expectNode((StructureItem node, StructureExpectations childExpectations) -> {
                assertEquals("node.name", content, node.getName());

                assertEquals("startOffset", token.getStartIndex(), node.getPosition());
                assertEquals("endOffset", endPos, node.getEndPosition());

                verifier.expectNode(node, childExpectations);
            });
        }

        public void verifyNodes(List<? extends StructureItem> nodes) throws Exception {
            int verifyCount = 0;

            for (StructureItem node: nodes) {
                try {
                    if (verifyCount >= nodeVerifiers.size()) {
                        throw new AssertionError("Expected " + nodeVerifiers.size() + " but received " + nodes.size());
                    }

                    NodeExpectation expectation = nodeVerifiers.get(verifyCount);
                    expectation.verifyNode(node);
                } catch (Throwable ex) {
                    throw new AssertionError("Failure for node: " + node.getName(), ex);
                }

                verifyCount++;
            }

            assertEquals("node count", nodeVerifiers.size(), verifyCount);
        }
    }

    private static final class NodeExpectation {
        private final NodeVerifier verifier;

        public NodeExpectation(NodeVerifier verifier) {
            this.verifier = verifier;
        }

        public void verifyNode(StructureItem node) throws Exception {
            StructureExpectations childExpectations = new StructureExpectations();
            verifier.expectNode(node, childExpectations);

            childExpectations.verifyNodes(node.getNestedItems());
        }
    }

    private interface NodeVerifier {
        public static final NodeVerifier NO_OP = (node, childExpectations) -> { };

        public void expectNode(StructureItem node, StructureExpectations childExpectations) throws Exception;
    }

    private interface TokenListSetup {
        public void setupTest(TokenListBuilder tokens, StructureExpectations expectations) throws Exception;
    }
}