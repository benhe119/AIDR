package qa.qcri.aidr.data.service;

import java.util.List;

import qa.qcri.aidr.data.persistence.entity.UserConnection;

public interface UserConnectionService {
	public void register (UserConnection userConnection);
	
    public List<UserConnection> getByProviderIdAndUserId (String providerId , String userId);

    public void update (UserConnection userConnection);

}
