package io.onedev.server.event.issue;

import java.util.Collection;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.UrlManager;
import io.onedev.server.model.IssueComment;
import io.onedev.server.persistence.dao.Dao;

public class IssueCommented extends IssueEvent {

	private final IssueComment comment;
	
	private final Collection<String> notifiedEmailAddresses;
	
	public IssueCommented(IssueComment comment, Collection<String> notifiedEmailAddresses) {
		super(comment.getUser(), comment.getDate(), comment.getIssue());
		this.comment = comment;
		this.notifiedEmailAddresses = notifiedEmailAddresses;
	}

	public IssueComment getComment() {
		return comment;
	}

	@Override
	public String getMarkdown() {
		return getComment().getContent();
	}

	@Override
	public boolean affectsListing() {
		return false;
	}

	public Collection<String> getNotifiedEmailAddresses() {
		return notifiedEmailAddresses;
	}

	@Override
	public String getActivity() {
		return "commented";
	}

	@Override
	public IssueEvent cloneIn(Dao dao) {
		return new IssueCommented(dao.load(IssueComment.class, comment.getId()), notifiedEmailAddresses);
	}

	@Override
	public String getUrl() {
		return OneDev.getInstance(UrlManager.class).urlFor(getComment());
	}
	
}
