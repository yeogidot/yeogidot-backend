package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TravelRepository extends JpaRepository<Travel, Long> {

    List<Travel> findAllByUserOrderByTravelIdDesc(User user);
}