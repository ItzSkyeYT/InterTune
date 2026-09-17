The pipelines on my fork cannot run. GitLab shows "Before you can run pipelines, we need to verify your account" and asks for identity verification, so both the branch push and the merge request pipeline finish instantly with zero jobs and a misleading "yaml invalid" badge. Per the note in the inclusion template I have not submitted anything, and am flagging it here so the CI can be triggered from your side.

In the meantime the metadata was checked locally with fdroidserver 2.4.5. `fdroid readmeta` parses it, and `fdroid lint` reports nothing that the existing com.dd3boh.outertune entry does not also report when linted under the same bare config.

/label ~"New App"
