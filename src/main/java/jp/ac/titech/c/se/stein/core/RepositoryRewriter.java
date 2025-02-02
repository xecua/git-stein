package jp.ac.titech.c.se.stein.core;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.Application.Config;
import jp.ac.titech.c.se.stein.core.Context.Key;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import picocli.CommandLine.Option;

public class RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(RepositoryRewriter.class);

    protected static final ObjectId ZERO = ObjectId.zeroId();

    /**
     * Entry-to-entries mapping.
     */
    protected Map<Entry, EntrySet> entryMapping = new HashMap<>();

    /**
     * Commit-to-commit mapping.
     */
    protected Map<ObjectId, ObjectId> commitMapping = new HashMap<>();

    /**
     * Tag-to-tag mapping.
     */
    protected final Map<ObjectId, ObjectId> tagMapping = new HashMap<>();

    /**
     * Ref-to-ref mapping.
     */
    protected final Map<RefEntry, RefEntry> refEntryMapping = new HashMap<>();

    protected RepositoryAccess source, target;

    protected boolean isOverwriting = false;

    protected boolean isPathSensitive = false;

    @Option(names = { "-p", "--parallel" }, paramLabel = "<nthreads>", description = "number of threads to rewrite trees in parallel", order = Config.MIDDLE,
            fallbackValue = "0")
    protected int nthreads = 1;

    @Option(names = { "-n", "--dry-run" }, description = "do not actually touch destination repo", order = Config.MIDDLE)
    protected boolean isDryRunning = false;

    @Option(names = "--notes-forward", negatable = true, description = "note rewritten commits to source repo", order = Config.MIDDLE)
    protected boolean isAddingForwardNotes = false;

    @Option(names = "--no-notes-backward", negatable = true, description = "note original commits to destination repo", order = Config.MIDDLE)
    protected boolean isAddingBackwardNotes = true;

    @Option(names = "--extra-attributes", description = "rewrite encoding and signature in commits", order = Config.MIDDLE)
    protected boolean isRewritingExtraAttributes = false;

    enum CacheLevel {
        blob, tree, commit
    }

    @Option(names = "--cache-level", arity = "0..*", description = {
        "granularity of cache save for incremental conversion",
        "Valid values: ${COMPLETION-CANDIDATES}"
    }, order = Config.MIDDLE)
    protected EnumSet<CacheLevel> cacheLevel = EnumSet.noneOf(CacheLevel.class);
    protected CacheProvider cacheProvider;

    public void initialize(final Repository sourceRepo, final Repository targetRepo) {
        source = new RepositoryAccess(sourceRepo);
        target = new RepositoryAccess(targetRepo);
        isOverwriting = sourceRepo == targetRepo;
        if (nthreads == 0) {
            final int nprocs = Runtime.getRuntime().availableProcessors();
            nthreads = nprocs > 1 ? nprocs - 1 : 1;
        }
        if (nthreads > 1) {
            this.entryMapping = new ConcurrentHashMap<>();
        }
        if (isDryRunning) {
            source.setDryRunning(true);
            target.setDryRunning(true);
        }
        if (cacheLevel.size() != 0) {
            cacheProvider = new CacheProvider.SQLiteCacheProvider(targetRepo);
        }
    }

    public void rewrite(final Context c) {
        setUp(c);
        if (cacheLevel.size() != 0) {
            loadCache(c);
        }
        rewriteCommits(c);
        updateRefs(c);
        source.writeNotes(c);
        target.writeNotes(c);
        if (cacheLevel.size() != 0) {
            saveCache(c);
        }
        cleanUp(c);
    }

    protected void setUp(final Context c) {}

    /**
     * Rewrites all commits.
     */
    protected void rewriteCommits(final Context c) {
        rewriteRootTrees(c);

        target.openInserter(ins -> {
            final Context uc = c.with(Key.inserter, ins);
            try (final RevWalk walk = prepareRevisionWalk(uc)) {
                for (final RevCommit commit : walk) {
                    rewriteCommit(commit, uc);
                    commit.disposeBody();
                }
            }
        }, c);
    }

    /**
     * Rewrites all root trees.
     */
    protected void rewriteRootTrees(final Context c) {
        if (nthreads <= 1) {
            // This will be done in each rewriteCommit() in non-parallel mode.
            return;
        }

        final Map<Long, Context> cxts = new ConcurrentHashMap<>();
        final ExecutorService pool = Executors.newFixedThreadPool(nthreads);
        try (final RevWalk walk = prepareRevisionWalk(c)) {
            for (final RevCommit commit : walk) {
                pool.execute(() -> {
                    final long id = Thread.currentThread().getId();
                    final Context uc = cxts.computeIfAbsent(id, k -> c.with(Key.inserter, target.getInserter(c)));
                    final Context uuc = uc.with(Key.rev, commit, Key.commit, commit);
                    rewriteRootTree(commit.getTree().getId(), uuc);
                    commit.disposeBody();
                });
            }
        }
        pool.shutdown();

        // Long.MAX_VALUE means infinity.
        // @see java.util.concurrent
        Try.run(() -> pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS));

        // finalize
        for (final Context uc : cxts.values()) {
            uc.getInserter().close();
        }
    }

    /**
     * Prepares the revision walk.
     */
    protected RevWalk prepareRevisionWalk(final Context c) {
        final Collection<ObjectId> starts = collectStarts(c);
        final Collection<ObjectId> uninterestings = collectUninterestings(c);

        if (cacheLevel.contains(CacheLevel.commit)) {
            uninterestings.addAll(cacheProvider
                .getAllCommits(c)
                .stream()
                .map(pair -> {
                    // FIXME using side effect
                    source.addNote(pair.getLeft(), getForwardNote(pair.getRight(), c), c);
                    target.addNote(pair.getRight(), getBackwardNote(pair.getLeft(), c), c);
                    return pair.getLeft();
                })
                .collect(Collectors.toList())
            );
        }

        final RevWalk walk = source.walk(c);
        Try.io(c, () -> {
            for (final ObjectId id : starts) {
                walk.markStart(walk.parseCommit(id));
            }
            for (final ObjectId id : uninterestings) {
                walk.markUninteresting(walk.parseCommit(id));
            }
        });
        return walk;
    }

    /**
     * Collects the set of commit Ids used as start points.
     */
    protected Collection<ObjectId> collectStarts(final Context c) {
        final List<ObjectId> result = new ArrayList<>();
        for (final Ref ref : source.getRefs(c)) {
            if (confirmStartRef(ref, c)) {
                final ObjectId commitId = source.getRefTarget(ref, c);
                if (source.getObjectType(commitId, c) == Constants.OBJ_COMMIT) {
                    log.debug("Ref {}: added as a start point (commit: {})", ref.getName(), commitId.name());
                    result.add(commitId);
                } else {
                    log.debug("Ref {}: non-commit; skipped (commit: {})", ref.getName(), commitId.name());
                }
            }
        }
        return result;
    }

    /**
     * Confirms whether the given ref is used for a start point.
     */
    protected boolean confirmStartRef(final Ref ref, final Context c) {
        final String name = ref.getName();
        return name.equals(Constants.HEAD) || name.startsWith(Constants.R_HEADS) || name.startsWith(Constants.R_TAGS);
    }

    /**
     * Collects the set of commit Ids used as uninteresting points.
     */
    protected Collection<ObjectId> collectUninterestings(final Context c) {
        return new ArrayList<>();
    }

    /**
     * Rewrites a commit.
     *
     * @param commit target commit.
     * @return the object ID of the rewritten commit
     */
    protected ObjectId rewriteCommit(final RevCommit commit, final Context c) {
        final Context uc = c.with(Key.rev, commit, Key.commit, commit);
        final ObjectId[] parentIds = rewriteParents(commit.getParents(), uc);
        final ObjectId treeId = rewriteRootTree(commit.getTree().getId(), uc);
        final PersonIdent author = rewriteAuthor(commit.getAuthorIdent(), uc);
        final PersonIdent committer = rewriteCommitter(commit.getCommitterIdent(), uc);
        final String msg = rewriteCommitMessage(commit.getFullMessage(), uc);
        ObjectId newId;
        if (isRewritingExtraAttributes) {
            final Charset enc = rewriteEncoding(commit.getEncoding(), uc);
            final GpgSignature sig = rewriteSignature(commit.getRawGpgSignature(), uc);
            newId = target.writeCommit(parentIds, treeId, author, committer, enc, sig, msg, uc);
        } else {
            newId = target.writeCommit(parentIds, treeId, author, committer, msg, uc);
        }

        final ObjectId oldId = commit.getId();
        commitMapping.put(oldId, newId);
        log.debug("Rewrite commit: {} -> {} {}", oldId.name(), newId.name(), c);

        source.addNote(oldId, getForwardNote(newId, c), uc);
        target.addNote(newId, getBackwardNote(oldId, c), uc);

        return newId;
    }

    /**
     * Returns a note for a commit.
     */
    protected String getForwardNote(final ObjectId newCommitId, final Context c) {
        return isAddingForwardNotes ? newCommitId.name() : null;
    }

    /**
     * Returns a note for a commit.
     */
    protected String getBackwardNote(final ObjectId oldCommitId, final Context c) {
        return isAddingBackwardNotes ? oldCommitId.name() : null;
    }

    /**
     * Rewrites the parents of a commit.
     */
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        final ObjectId[] result = new ObjectId[parents.length];
        for (int i = 0; i < parents.length; i++) {
            final ObjectId parent = parents[i];
            final ObjectId newParent = commitMapping.get(parent);
            if (newParent == null) {
                log.warn("Parent commit has not rewritten yet: {} {}", parent.name(), c);
                result[i] = parent;
            } else {
                result[i] = newParent;
            }
        }
        return result;
    }

    /**
     * Rewrites the root tree of a commit.
     */
    protected ObjectId rewriteRootTree(final ObjectId treeId, final Context c) {
        // A root tree is represented as a special entry whose name is "/"
        final Entry root = new Entry(FileMode.TREE.getBits(), "", treeId, isPathSensitive ? "" : null);
        final EntrySet newRoot = getEntry(root, c);
        final ObjectId newId = newRoot == EntrySet.EMPTY ? target.writeTree(Collections.emptyList(), c) : ((Entry) newRoot).id;

        log.debug("Rewrite tree: {} -> {} {}", treeId.name(), newId.name(), c);
        return newId;
    }

    /**
     * Obtains tree entries from a tree entry.
     */
    protected EntrySet getEntry(final Entry entry, final Context c) {
        // computeIfAbsent is unsuitable because this may be invoked recursively
        final EntrySet cache = entryMapping.get(entry);
        if (cache != null) {
            return cache;
        }
        if ((cacheLevel.contains(CacheLevel.tree) && entry.isTree()) ||
            (cacheLevel.contains(CacheLevel.blob) && !entry.isTree())) {
            final Optional<EntrySet> maybeNewEntry = cacheProvider.getFromSourceEntry(entry, c);
            if (maybeNewEntry.isPresent()) {
                entryMapping.put(entry, maybeNewEntry.get());
                return maybeNewEntry.get();
            }
        }
        final EntrySet result = rewriteEntry(entry, c);
        entryMapping.put(entry, result);
        if ((cacheLevel.contains(CacheLevel.tree) && entry.isTree()) ||
            (cacheLevel.contains(CacheLevel.blob) && !entry.isTree())) {
            cacheProvider.registerEntry(entry, result, c);
        }
        return result;
    }

    /**
     * Rewrites a tree entry.
     */
    protected EntrySet rewriteEntry(final Entry entry, final Context c) {
        final Context uc = c.with(Key.entry, entry);

        final ObjectId newId = entry.isTree() ? rewriteTree(entry.id, uc) : rewriteBlob(entry.id, uc);
        final String newName = rewriteName(entry.name, uc);
        return newId == ZERO ? EntrySet.EMPTY : new Entry(entry.mode, newName, newId, entry.directory);
    }

    /**
     * Rewrites a tree object.
     */
    protected ObjectId rewriteTree(final ObjectId treeId, final Context c) {
        final Entry entry = c.getEntry();
        final String path = entry.isRoot() ? "" : c.getPath() + "/" + entry.name;
        final Context uc = c.with(Key.path, path);

        final String dir = isPathSensitive ? path : null;

        final List<Entry> entries = new ArrayList<>();
        for (final Entry e : source.readTree(treeId, dir, uc)) {
            final EntrySet rewritten = getEntry(e, uc);
            rewritten.registerTo(entries);
        }
        final ObjectId newId = entries.isEmpty() ? ZERO : target.writeTree(entries, uc);
        if (log.isDebugEnabled() && !newId.equals(treeId)) {
            log.debug("Rewrite tree: {} -> {} {}", treeId.name(), newId.name(), c);
        }
        return newId;
    }

    /**
     * Rewrites a blob object.
     */
    protected ObjectId rewriteBlob(final ObjectId blobId, final Context c) {
        if (isOverwriting) {
            return blobId;
        }
        final ObjectId newId = target.writeBlob(source.readBlob(blobId, c), c);
        if (log.isDebugEnabled() && !newId.equals(blobId)) {
            log.debug("Rewrite blob: {} -> {} {}", blobId.name(), newId.name(), c);
        }
        return newId;
    }

    /**
     * Rewrites the name of a tree entry.
     */
    protected String rewriteName(final String name, final Context c) {
        return name;
    }

    /**
     * Rewrites the author identity of a commit.
     */
    protected PersonIdent rewriteAuthor(final PersonIdent author, final Context c) {
        return rewritePerson(author, c);
    }

    /**
     * Rewrites the committer identity of a commit.
     */
    protected PersonIdent rewriteCommitter(final PersonIdent committer, final Context c) {
        return rewritePerson(committer, c);
    }

    /**
     * Rewrites a person identity.
     */
    protected PersonIdent rewritePerson(final PersonIdent person, final Context c) {
        return person;
    }

    /**
     * Rewrites the message of a commit.
     */
    protected String rewriteCommitMessage(final String message, final Context c) {
        return rewriteMessage(message, c);
    }

    /**
     * Rewrites a message.
     */
    protected String rewriteMessage(final String message, final Context c) {
        return message;
    }

    /**
     * Rewrites an encoding.
     */
    protected Charset rewriteEncoding(final Charset encoding, final Context c) {
        return encoding;
    }

    /**
     * Rewrites a GPG signature.
     */
    protected GpgSignature rewriteSignature(final byte[] rawSignature, final Context c) {
        if (rawSignature != null) {
            // fix to remove the prefix spaces
            final byte[] fixed = new String(rawSignature, US_ASCII).replaceAll("\n ", "\n").getBytes();
            return new GpgSignature(fixed);
        } else {
            return null;
        }
    }

    /**
     * Updates ref objects.
     */
    protected void updateRefs(final Context c) {
        for (final Ref ref : source.getRefs(c)) {
            if (confirmUpdateRef(ref, c)) {
                updateRef(ref, c);
            }
        }
    }

    /**
     * Confirms whether the given ref is to be updated.
     */
    protected boolean confirmUpdateRef(final Ref ref, final Context c) {
        return confirmStartRef(ref, c);
    }

    /**
     * Updates a ref object.
     */
    protected RefEntry getRefEntry(final RefEntry entry, final Context c) {
        final RefEntry cache = refEntryMapping.get(entry);
        if (cache != null) {
            return cache;
        } else {
            final RefEntry result = rewriteRefEntry(entry, c);
            refEntryMapping.put(entry, result);
            return result;
        }
    }

    /**
     * Updates a ref object.
     */
    protected void updateRef(final Ref ref, final Context c) {
        final Context uc = c.with(Key.ref, ref);

        final RefEntry oldEntry = new RefEntry(ref);
        final RefEntry newEntry = getRefEntry(oldEntry, uc);
        if (newEntry == RefEntry.EMPTY) {
            // delete
            if (isOverwriting) {
                log.debug("Delete ref: {} {}", oldEntry, c);
                target.applyRefDelete(oldEntry, uc);
            }
            return;
        }

        if (!oldEntry.name.equals(newEntry.name)) {
            // rename
            if (isOverwriting) {
                log.debug("Rename ref: {} -> {} {}", oldEntry.name, newEntry.name, c);
                target.applyRefRename(oldEntry.name, newEntry.name, uc);
            }
        }

        final boolean linkEquals = oldEntry.target == null ? newEntry.target == null : oldEntry.target.equals(newEntry.target);
        final boolean idEquals = oldEntry.id == null ? newEntry.id == null : oldEntry.id.name().equals(newEntry.id.name());

        if (!isOverwriting || !linkEquals || !idEquals) {
            // update
            log.debug("Update ref: {} -> {} {}", oldEntry, newEntry, c);
            target.applyRefUpdate(newEntry, uc);
        }
    }

    /**
     * Rewrites a ref entry.
     */
    protected RefEntry rewriteRefEntry(final RefEntry entry, final Context c) {
        if (entry.isSymbolic()) {
            final String newName = rewriteRefName(entry.name, c);

            final Ref targetRef = c.getRef().getTarget();
            final Context uc = c.with(Key.ref, targetRef);
            final String newTarget = getRefEntry(new RefEntry(targetRef), uc).name;
            return new RefEntry(newName, newTarget);
        } else {
            final String newName = rewriteRefName(entry.name, c);
            final int type = source.getObjectType(entry.id, c);
            final ObjectId newObjectId = rewriteRefObject(entry.id, type, c);
            return newObjectId == ZERO ? RefEntry.EMPTY : new RefEntry(newName, newObjectId);
        }
    }

    /**
     * Rewrites the referred object by a ref or a tag.
     */
    protected ObjectId rewriteRefObject(final ObjectId id, final int type, final Context c) {
        switch (type) {
        case Constants.OBJ_TAG:
            final ObjectId newTagId = tagMapping.get(id);
            return newTagId != null ? newTagId : rewriteTag(source.parseTag(id, c), c);

        case Constants.OBJ_COMMIT:
            final ObjectId newCommitId = commitMapping.get(id);
            if (newCommitId == null) {
                log.warn("Rewritten commit not found: {} {}", id.name(), c);
                return id;
            }
            return newCommitId;

        default:
            // referring non-commit and non-tag; ignore it
            log.warn("Ignore unknown type: {}, type = {} {}", id.name(), type, c);
            return id;
        }
    }

    /**
     * Rewrites a tag object.
     */
    protected ObjectId rewriteTag(final RevTag tag, final Context c) {
        final Context uc = c.with(Key.rev, tag, Key.tag, tag);
        final ObjectId oldId = tag.getId();

        final ObjectId oldObjectId = tag.getObject().getId();
        final int type = source.getObjectType(oldObjectId, c);
        final ObjectId newObjectId = rewriteRefObject(oldObjectId, type, uc);
        if (newObjectId == ZERO) {
            log.debug("Delete tag {} due to its object to be deleted {}", oldId, c);
            return ZERO;
        }
        log.debug("Rewrite tag object: {} -> {} {}", oldObjectId.name(), newObjectId.name(), c);

        final String tagName = tag.getTagName();
        final PersonIdent tagger = rewriteTagger(tag.getTaggerIdent(), tag, uc);
        final String message = rewriteTagMessage(tag.getFullMessage(), uc);
        final ObjectId newId = target.writeTag(newObjectId, type, tagName, tagger, message, uc);
        log.debug("Rewrite tag: {} -> {} {}", oldId.name(), newId.name(), c);

        tagMapping.put(oldId, newId);
        return newId;
    }

    /**
     * Rewrites the tagger identity of a tag.
     */
    protected PersonIdent rewriteTagger(final PersonIdent tagger, final RevTag tag, final Context c) {
        return rewritePerson(tagger, c);
    }

    /**
     * Rewrites the message of a tag.
     */
    protected String rewriteTagMessage(final String message, final Context c) {
        return rewriteMessage(message, c);
    }

    /**
     * Rewrites a ref name.
     */
    protected String rewriteRefName(final String name, final Context c) {
        if (name.startsWith(Constants.R_HEADS)) {
            final String branchName = name.substring(Constants.R_HEADS.length());
            return Constants.R_HEADS + rewriteBranchName(branchName, c);
        } else if (name.startsWith(Constants.R_TAGS)) {
            final String tagName = name.substring(Constants.R_TAGS.length());
            return Constants.R_TAGS + rewriteTagName(tagName, c);
        } else {
            return name;
        }
    }

    /**
     * Rewrites a local branch name.
     */
    protected String rewriteBranchName(final String name, final Context c) {
        return name;
    }

    /**
     * Rewrites a tag name.
     */
    protected String rewriteTagName(final String name, final Context c) {
        return name;
    }

    /**
     * A hook method for cleaning up.
     */
    protected void cleanUp(final Context c) {}

    /**
     * Exports source-to-destination mapping of commits.
     */
    public Map<String, String> exportCommitMapping() {
        final Map<String, String> result = new HashMap<>();
        for (final Map.Entry<ObjectId, ObjectId> e : commitMapping.entrySet()) {
            final String src = e.getKey().name();
            final String dst = e.getValue().name();
            result.put(src, dst);
        }
        return result;
    }

    /**
     * Imports source-to-destination mapping of commits.
     */
    public void importCommitMapping(final Map<String, String> map) {
        commitMapping.clear();
        for (final Map.Entry<String, String> e : map.entrySet()) {
            final ObjectId src = ObjectId.fromString(e.getKey());
            final ObjectId dst = ObjectId.fromString(e.getValue());
            commitMapping.put(src, dst);
        }
    }

    public void loadCache(final Context c) {
        if (cacheLevel.contains(CacheLevel.commit)) {
            // FIXME modify commitMapping directly
            commitMapping = Try.io(c, () -> cacheProvider.readToCommitMapping(c));
        }
    }

    public void saveCache(final Context c) {
        if (cacheLevel.contains(CacheLevel.commit)) {
            Try.io(c, () -> cacheProvider.writeOutFromCommitMapping(commitMapping, c));
        }
    }
}
