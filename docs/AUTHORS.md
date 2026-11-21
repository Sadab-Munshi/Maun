# Who wrote this repository

The author is **Sadab-Munshi**, GitHub account ID `237267786`.

## Why an unfamiliar name appears in the contributors list

GitHub decides whose face to put next to a commit by the **author email** in
that commit. If the address is one of GitHub's `users.noreply.github.com`
aliases, GitHub resolves it by the *name part*: `<name>@users.noreply.github.com`
belongs to whichever account is called `<name>`.

As counted at `acc3494` (2026-11-16), some commits here were made with plain
git and an address typed by hand:

```
Sadab Munshi <sadab@users.noreply.github.com>           34 commits
Sadab Munshi <sadabmunshi@users.noreply.github.com>      6 commits
```

`sadab` is **not** this project's account. It is a different, unrelated GitHub
account (ID `4946861`, display name "Sadab Rahman Michael"), and GitHub
therefore credits those 34 commits to him. Nothing in the repository is shared
with that account — `GET /repos/Sadab-Munshi/Maun/collaborators` lists exactly
one collaborator, `Sadab-Munshi`, as admin, and no other account has access.

Eleven commits carry the author `Sadab Munshi <sadabmunsiusa999@gmail.com>`
(the count at this checkout). They were made through the GitHub web interface
("Add files via upload", and merges of pull requests from this repository's own
branch). GitHub attributes an address to an account only when that address is
verified on it, so these are either already attributed correctly or shown as an
unattributed name. Five more are authored by the repository's release bot
identity, `Maun <noreply@maun.example.org>`. They
are deliberately **not** mapped in `.mailmap`, because claiming an email address
for an author is something only its owner can do.

## What was fixed

- **Future commits** are made as
  `Sadab-Munshi <237267786+Sadab-Munshi@users.noreply.github.com>` — the address
  GitHub gives an account, derived from the account ID, which no one else can
  own. This is the address 68 commits already use at this checkout, and GitHub credits it to
  `Sadab-Munshi`.
- **`.mailmap`** maps the two mistaken noreply addresses back to the author, so
  `git log`, `git shortlog -sn` and `git blame` name the right person.
  `git shortlog -sn` at `acc3494` shows 108 commits for Sadab-Munshi, 11 for the
  web-interface address, and 5 for the release-bot identity; the author count
  will change as new commits land.

`.mailmap` changes what git prints. It does not touch a single commit, tag or
signature, and it does not change the contributors list on GitHub, which reads
the author address stored in each commit.

## Correcting the 34 commits themselves

Only a history rewrite can change the address inside existing commits. That
means rewriting `main` and force-pushing it, which:

- gives every commit from the first affected one a new hash, and detaches
  every tag and release that pointed at the old ones (including `v0.24.0`,
  whose APKs are already published and would stay downloadable, but whose tag
  would have to be moved by hand);
- breaks every existing clone and open pull request;
- leaves the release workflow's provenance (`github.sha` in the build logs)
  pointing at commits that no longer exist.

It is technically possible and it would fix the contributors list. It is also
the one operation in this repository that cannot be undone by a later commit, so
it is not done without an explicit decision from the repository owner.
