package com.aiopenplatform.service.impl;

import com.aiopenplatform.entity.BlogComments;
import com.aiopenplatform.mapper.BlogCommentsMapper;
import com.aiopenplatform.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
