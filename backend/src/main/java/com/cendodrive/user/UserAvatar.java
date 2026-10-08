package com.cendodrive.user;

import jakarta.persistence.*;

@Entity
@Table(name="user_avatars")
public class UserAvatar {
  @Id @Column(name="user_id") private Long userId;
  @Lob @Column(name="image_data",nullable=false,columnDefinition="LONGBLOB") private byte[] imageData;
  protected UserAvatar() {}
  public UserAvatar(Long userId, byte[] imageData) { this.userId=userId; this.imageData=imageData; }
  public byte[] getImageData() { return imageData; }
}
